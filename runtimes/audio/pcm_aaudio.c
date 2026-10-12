/* SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * ALSA ioplug PCM plugin: playback straight to Android AAudio (no sound server).
 * Built by scripts/build-audio.sh as libasound_module_pcm_aaudio.so; PanPlay's generated asound.conf
 * makes it the slave of pcm.!default (type plug, which converts any other format/rate/channel count).
 *
 * Data path: snd_pcm_writei -> transfer() copies into a ring of the ALSA buffer size at appl_ptr;
 * AAudio's low-latency data callback consumes it at hw and pads underruns with silence (never XRUN).
 * hw/wr are frame counters modulo the ALSA boundary (SND_PCM_IOPLUG_FLAG_BOUNDARY_WA), so a slow
 * pointer() caller can never lose a whole-buffer wrap. A disconnected stream (route change) reports
 * -EPIPE from pointer(); the app's snd_pcm_recover() -> prepare() reopens it.
 *
 * Config (all optional):
 *   pcm.aaudio { type aaudio  buffer_bursts 0 }   # >0: AAudio buffer = N bursts, 0 = AAudio default
 */
#include <errno.h>
#include <poll.h>
#include <pthread.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/eventfd.h>
#include <unistd.h>
#include <android/log.h>
#include <aaudio/AAudio.h>
#include <alsa/asoundlib.h>
#include <alsa/pcm_external.h>

#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "pcm_aaudio", __VA_ARGS__)

typedef struct {
	snd_pcm_ioplug_t io;
	AAudioStream *stream;
	int efd;
	int buffer_bursts;
	unsigned int frame_bytes;
	snd_pcm_uframes_t bufsize, boundary;
	char *ring;
	/* written end (producer: transfer) and consumed position (consumer: AAudio callback) */
	snd_pcm_uframes_t wr, hw;
	int running;          /* callback consumes only between start and stop */
	int disconnected;     /* AAudio error callback fired; reopen in prepare */
	unsigned long underruns;
} snd_pcm_aaudio_t;

static snd_pcm_uframes_t sub(const snd_pcm_aaudio_t *a, snd_pcm_uframes_t x, snd_pcm_uframes_t y)
{
	return x >= y ? x - y : a->boundary - y + x;
}

static aaudio_data_callback_result_t data_cb(AAudioStream *s, void *ud, void *out, int32_t frames)
{
	snd_pcm_aaudio_t *a = ud;
	char *dst = out;
	size_t fb = a->frame_bytes;
	snd_pcm_uframes_t n = 0;
	(void)s;
	if (__atomic_load_n(&a->running, __ATOMIC_ACQUIRE)) {
		snd_pcm_uframes_t h = __atomic_load_n(&a->hw, __ATOMIC_RELAXED);
		snd_pcm_uframes_t w = __atomic_load_n(&a->wr, __ATOMIC_ACQUIRE);
		snd_pcm_uframes_t avail = sub(a, w, h);
		if (avail > a->bufsize)
			avail = 0; /* rewound below hw: drop, the producer rewrites */
		n = avail < (snd_pcm_uframes_t)frames ? avail : (snd_pcm_uframes_t)frames;
		snd_pcm_uframes_t pos = h % a->bufsize, left = n;
		while (left) {
			snd_pcm_uframes_t c = a->bufsize - pos < left ? a->bufsize - pos : left;
			memcpy(dst, a->ring + pos * fb, c * fb);
			dst += c * fb;
			left -= c;
			pos = (pos + c) % a->bufsize;
		}
		if (n < (snd_pcm_uframes_t)frames)
			a->underruns++;
		__atomic_store_n(&a->hw, (h + n) % a->boundary, __ATOMIC_RELEASE);
		uint64_t one = 1;
		if (n)
			(void)!write(a->efd, &one, sizeof(one));
	}
	memset(dst, 0, ((size_t)frames - n) * fb);
	return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

static void error_cb(AAudioStream *s, void *ud, aaudio_result_t err)
{
	snd_pcm_aaudio_t *a = ud;
	(void)s;
	LOG("stream error %d (%s)", err, AAudio_convertResultToText(err));
	__atomic_store_n(&a->disconnected, 1, __ATOMIC_RELEASE);
	uint64_t one = 1;
	(void)!write(a->efd, &one, sizeof(one)); /* wake a blocked writer so it sees -EPIPE */
}

static void close_stream(snd_pcm_aaudio_t *a)
{
	if (a->stream) {
		AAudioStream_requestStop(a->stream);
		AAudioStream_close(a->stream);
		a->stream = NULL;
	}
}

static int open_stream(snd_pcm_aaudio_t *a)
{
	AAudioStreamBuilder *b;
	aaudio_result_t r = AAudio_createStreamBuilder(&b);
	if (r != AAUDIO_OK)
		return -ENODEV;
	AAudioStreamBuilder_setDirection(b, AAUDIO_DIRECTION_OUTPUT);
	AAudioStreamBuilder_setPerformanceMode(b, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
	AAudioStreamBuilder_setSharingMode(b, AAUDIO_SHARING_MODE_SHARED);
	AAudioStreamBuilder_setUsage(b, AAUDIO_USAGE_GAME);
	AAudioStreamBuilder_setContentType(b, AAUDIO_CONTENT_TYPE_MUSIC);
	AAudioStreamBuilder_setFormat(b, a->io.format == SND_PCM_FORMAT_FLOAT_LE ?
				      AAUDIO_FORMAT_PCM_FLOAT : AAUDIO_FORMAT_PCM_I16);
	AAudioStreamBuilder_setChannelCount(b, a->io.channels);
	AAudioStreamBuilder_setSampleRate(b, a->io.rate);
	AAudioStreamBuilder_setDataCallback(b, data_cb, a);
	AAudioStreamBuilder_setErrorCallback(b, error_cb, a);
	r = AAudioStreamBuilder_openStream(b, &a->stream);
	AAudioStreamBuilder_delete(b);
	if (r != AAUDIO_OK) {
		LOG("openStream failed: %s", AAudio_convertResultToText(r));
		a->stream = NULL;
		return -ENODEV;
	}
	int32_t burst = AAudioStream_getFramesPerBurst(a->stream);
	if (a->buffer_bursts > 0 && burst > 0)
		AAudioStream_setBufferSizeInFrames(a->stream, burst * a->buffer_bursts);
	__atomic_store_n(&a->disconnected, 0, __ATOMIC_RELEASE);
	LOG("open %u Hz %u ch %s, alsa buffer %lu period %lu, aaudio burst %d buffer %d/%d pm %d sharing %d",
	    a->io.rate, a->io.channels, snd_pcm_format_name(a->io.format), a->io.buffer_size, a->io.period_size,
	    burst, AAudioStream_getBufferSizeInFrames(a->stream), AAudioStream_getBufferCapacityInFrames(a->stream),
	    AAudioStream_getPerformanceMode(a->stream), AAudioStream_getSharingMode(a->stream));
	return 0;
}

static int aa_hw_params(snd_pcm_ioplug_t *io, snd_pcm_hw_params_t *params)
{
	snd_pcm_aaudio_t *a = io->private_data;
	(void)params;
	close_stream(a);
	a->frame_bytes = snd_pcm_format_physical_width(io->format) / 8 * io->channels;
	a->bufsize = io->buffer_size;
	free(a->ring);
	a->ring = calloc(a->bufsize, a->frame_bytes);
	if (!a->ring)
		return -ENOMEM;
	return open_stream(a);
}

static int aa_hw_free(snd_pcm_ioplug_t *io)
{
	snd_pcm_aaudio_t *a = io->private_data;
	close_stream(a);
	free(a->ring);
	a->ring = NULL;
	return 0;
}

static int aa_prepare(snd_pcm_ioplug_t *io)
{
	snd_pcm_aaudio_t *a = io->private_data;
	snd_pcm_sw_params_t *sw;
	snd_pcm_sw_params_alloca(&sw);
	if (snd_pcm_sw_params_current(io->pcm, sw) == 0)
		snd_pcm_sw_params_get_boundary(sw, &a->boundary);
	if (!a->boundary)
		a->boundary = a->bufsize;
	__atomic_store_n(&a->running, 0, __ATOMIC_RELEASE);
	if (!a->stream || __atomic_load_n(&a->disconnected, __ATOMIC_ACQUIRE)) {
		close_stream(a);
		if (open_stream(a) < 0)
			return -ENODEV;
	}
	a->hw = a->wr = 0; /* ioplug resets appl/hw to 0 before calling prepare */
	uint64_t v;
	(void)!read(a->efd, &v, sizeof(v));
	return 0;
}

static int aa_start(snd_pcm_ioplug_t *io)
{
	snd_pcm_aaudio_t *a = io->private_data;
	if (!a->stream)
		return -EBADFD;
	__atomic_store_n(&a->running, 1, __ATOMIC_RELEASE);
	aaudio_stream_state_t st = AAudioStream_getState(a->stream);
	if (st == AAUDIO_STREAM_STATE_STARTED || st == AAUDIO_STREAM_STATE_STARTING)
		return 0;
	aaudio_result_t r = AAudioStream_requestStart(a->stream);
	if (r != AAUDIO_OK) {
		LOG("requestStart failed: %s", AAudio_convertResultToText(r));
		return -EIO;
	}
	return 0;
}

static int aa_stop(snd_pcm_ioplug_t *io)
{
	snd_pcm_aaudio_t *a = io->private_data;
	__atomic_store_n(&a->running, 0, __ATOMIC_RELEASE);
	if (a->stream)
		AAudioStream_requestPause(a->stream); /* keeps the stream; start() resumes it */
	if (a->underruns)
		LOG("stop: %lu callback underruns", a->underruns);
	return 0;
}

static snd_pcm_sframes_t aa_pointer(snd_pcm_ioplug_t *io)
{
	snd_pcm_aaudio_t *a = io->private_data;
	if (__atomic_load_n(&a->disconnected, __ATOMIC_ACQUIRE))
		return -EPIPE;
	return __atomic_load_n(&a->hw, __ATOMIC_ACQUIRE);
}

static snd_pcm_sframes_t aa_transfer(snd_pcm_ioplug_t *io, const snd_pcm_channel_area_t *areas,
				     snd_pcm_uframes_t offset, snd_pcm_uframes_t size)
{
	snd_pcm_aaudio_t *a = io->private_data;
	size_t fb = a->frame_bytes;
	const char *src = (const char *)areas[0].addr + (areas[0].first + areas[0].step * offset) / 8;
	snd_pcm_uframes_t appl = io->appl_ptr, pos = appl % a->bufsize, left = size;
	while (left) {
		snd_pcm_uframes_t c = a->bufsize - pos < left ? a->bufsize - pos : left;
		memcpy(a->ring + pos * fb, src, c * fb);
		src += c * fb;
		left -= c;
		pos = (pos + c) % a->bufsize;
	}
	__atomic_store_n(&a->wr, (appl + size) % a->boundary, __ATOMIC_RELEASE);
	return size;
}

static int aa_poll_revents(snd_pcm_ioplug_t *io, struct pollfd *pfd, unsigned int nfds, unsigned short *revents)
{
	snd_pcm_aaudio_t *a = io->private_data;
	uint64_t v;
	(void)nfds;
	*revents = 0;
	if (pfd[0].revents & POLLIN) {
		(void)!read(a->efd, &v, sizeof(v));
		*revents = POLLOUT;
	}
	return 0;
}

static int aa_close(snd_pcm_ioplug_t *io)
{
	snd_pcm_aaudio_t *a = io->private_data;
	close_stream(a);
	free(a->ring);
	close(a->efd);
	free(a);
	return 0;
}

static const snd_pcm_ioplug_callback_t aa_callback = {
	.start = aa_start,
	.stop = aa_stop,
	.pointer = aa_pointer,
	.transfer = aa_transfer,
	.close = aa_close,
	.hw_params = aa_hw_params,
	.hw_free = aa_hw_free,
	.prepare = aa_prepare,
	.poll_revents = aa_poll_revents,
};

SND_PCM_PLUGIN_DEFINE_FUNC(aaudio)
{
	snd_config_iterator_t i, next;
	snd_pcm_aaudio_t *a;
	long bursts = 0;
	int err;
	(void)root;

	snd_config_for_each(i, next, conf) {
		snd_config_t *n = snd_config_iterator_entry(i);
		const char *id;
		if (snd_config_get_id(n, &id) < 0)
			continue;
		if (!strcmp(id, "comment") || !strcmp(id, "type") || !strcmp(id, "hint"))
			continue;
		if (!strcmp(id, "buffer_bursts")) {
			if (snd_config_get_integer(n, &bursts) < 0 || bursts < 0 || bursts > 64) {
				SNDERR("buffer_bursts must be 0..64");
				return -EINVAL;
			}
			continue;
		}
		SNDERR("Unknown field %s", id);
		return -EINVAL;
	}
	if (stream != SND_PCM_STREAM_PLAYBACK)
		return -ENOTSUP; /* playback only: no capture endpoint */

	a = calloc(1, sizeof(*a));
	if (!a)
		return -ENOMEM;
	a->buffer_bursts = (int)bursts;
	a->efd = eventfd(0, EFD_NONBLOCK | EFD_CLOEXEC);
	if (a->efd < 0) {
		free(a);
		return -errno;
	}
	a->io.version = SND_PCM_IOPLUG_VERSION;
	a->io.name = "AAudio output";
	a->io.callback = &aa_callback;
	a->io.private_data = a;
	a->io.poll_fd = a->efd;
	a->io.poll_events = POLLIN;
	a->io.mmap_rw = 0;
	a->io.flags = SND_PCM_IOPLUG_FLAG_BOUNDARY_WA;

	err = snd_pcm_ioplug_create(&a->io, name, stream, mode);
	if (err < 0) {
		close(a->efd);
		free(a);
		return err;
	}

	static const unsigned int access[] = { SND_PCM_ACCESS_RW_INTERLEAVED, SND_PCM_ACCESS_MMAP_INTERLEAVED };
	static const unsigned int formats[] = { SND_PCM_FORMAT_S16_LE, SND_PCM_FORMAT_FLOAT_LE };
	if ((err = snd_pcm_ioplug_set_param_list(&a->io, SND_PCM_IOPLUG_HW_ACCESS, 2, access)) < 0 ||
	    (err = snd_pcm_ioplug_set_param_list(&a->io, SND_PCM_IOPLUG_HW_FORMAT, 2, formats)) < 0 ||
	    (err = snd_pcm_ioplug_set_param_minmax(&a->io, SND_PCM_IOPLUG_HW_CHANNELS, 1, 2)) < 0 ||
	    (err = snd_pcm_ioplug_set_param_minmax(&a->io, SND_PCM_IOPLUG_HW_RATE, 8000, 192000)) < 0 ||
	    (err = snd_pcm_ioplug_set_param_minmax(&a->io, SND_PCM_IOPLUG_HW_PERIOD_BYTES, 64, 256 * 1024)) < 0 ||
	    (err = snd_pcm_ioplug_set_param_minmax(&a->io, SND_PCM_IOPLUG_HW_PERIODS, 2, 64)) < 0 ||
	    (err = snd_pcm_ioplug_set_param_minmax(&a->io, SND_PCM_IOPLUG_HW_BUFFER_BYTES, 256, 1024 * 1024)) < 0) {
		snd_pcm_ioplug_delete(&a->io);
		return err;
	}
	*pcmp = a->io.pcm;
	return 0;
}

SND_PCM_PLUGIN_SYMBOL(aaudio);
