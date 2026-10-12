/* Audio path probe: 440 Hz tone through WASAPI shared mode (the path XAudio2/FAudio, DirectSound and winmm use).
 * Prints the mix format, buffer size, stream latency and app-side underruns (padding hit 0) to stdout (run log).
 * Usage: wasapitone.exe [seconds=30]
 * Build: x86_64-w64-mingw32-clang -O2 -Wall wasapitone.c -o wasapitone-x86_64.exe -lole32 -luuid  (llvm-mingw)
 */
#define COBJMACROS
#include <windows.h>
#include <initguid.h>
#include <mmdeviceapi.h>
#include <audioclient.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>

static const GUID subtype_float = { 0x00000003, 0x0000, 0x0010, { 0x80, 0x00, 0x00, 0xaa, 0x00, 0x38, 0x9b, 0x71 } };
#define CHECK(x) do { HRESULT hr_ = (x); if (FAILED(hr_)) { printf("FAIL %s: 0x%08lx\n", #x, (unsigned long)hr_); fflush(stdout); return 1; } } while (0)

int main(int argc, char **argv)
{
    int secs = argc > 1 ? atoi(argv[1]) : 30;
    IMMDeviceEnumerator *en; IMMDevice *dev; IAudioClient *ac; IAudioRenderClient *rc; WAVEFORMATEX *wf;
    CHECK(CoInitializeEx(NULL, COINIT_MULTITHREADED));
    CHECK(CoCreateInstance(&CLSID_MMDeviceEnumerator, NULL, CLSCTX_ALL, &IID_IMMDeviceEnumerator, (void **)&en));
    CHECK(IMMDeviceEnumerator_GetDefaultAudioEndpoint(en, eRender, eConsole, &dev));
    CHECK(IMMDevice_Activate(dev, &IID_IAudioClient, CLSCTX_ALL, NULL, (void **)&ac));
    CHECK(IAudioClient_GetMixFormat(ac, &wf));
    int isfloat = wf->wFormatTag == WAVE_FORMAT_IEEE_FLOAT || (wf->wFormatTag == WAVE_FORMAT_EXTENSIBLE &&
        IsEqualGUID(&((WAVEFORMATEXTENSIBLE *)wf)->SubFormat, &subtype_float));
    printf("mix format: %lu Hz, %u ch, %u bit %s\n", wf->nSamplesPerSec, wf->nChannels, wf->wBitsPerSample, isfloat ? "float" : "pcm");
    REFERENCE_TIME def, min;
    CHECK(IAudioClient_GetDevicePeriod(ac, &def, &min));
    HANDLE ev = CreateEventW(NULL, FALSE, FALSE, NULL);
    CHECK(IAudioClient_Initialize(ac, AUDCLNT_SHAREMODE_SHARED, AUDCLNT_STREAMFLAGS_EVENTCALLBACK, 0, 0, wf, NULL));
    CHECK(IAudioClient_SetEventHandle(ac, ev));
    UINT32 bufsz; REFERENCE_TIME lat;
    CHECK(IAudioClient_GetBufferSize(ac, &bufsz));
    CHECK(IAudioClient_GetStreamLatency(ac, &lat));
    printf("device period %.1f ms (min %.1f), buffer %u frames (%.1f ms), stream latency %.1f ms\n",
           def / 1e4, min / 1e4, bufsz, bufsz * 1000.0 / wf->nSamplesPerSec, lat / 1e4);
    fflush(stdout);
    CHECK(IAudioClient_GetService(ac, &IID_IAudioRenderClient, (void **)&rc));
    double ph = 0, step = 2 * 3.14159265358979 * 440 / wf->nSamplesPerSec;
    long underruns = 0, frames = 0;
    CHECK(IAudioClient_Start(ac));
    DWORD t0 = GetTickCount();
    while (GetTickCount() - t0 < (DWORD)secs * 1000) {
        WaitForSingleObject(ev, 200);
        UINT32 pad; BYTE *p;
        if (FAILED(IAudioClient_GetCurrentPadding(ac, &pad))) break;
        if (pad == 0 && frames > 0) underruns++;
        UINT32 n = bufsz - pad;
        if (!n || FAILED(IAudioRenderClient_GetBuffer(rc, n, &p))) continue;
        for (UINT32 i = 0; i < n; i++, ph += step) {
            float v = 0.2f * (float)sin(ph);
            for (int c = 0; c < wf->nChannels; c++) {
                if (isfloat) ((float *)p)[i * wf->nChannels + c] = v;
                else ((short *)p)[i * wf->nChannels + c] = (short)(v * 32767);
            }
        }
        IAudioRenderClient_ReleaseBuffer(rc, n, 0);
        frames += n;
    }
    IAudioClient_Stop(ac);
    printf("played %ld frames (%.2f s) in %d s, app-side underruns %ld\n", frames, (double)frames / wf->nSamplesPerSec, secs, underruns);
    fflush(stdout);
    return 0;
}
