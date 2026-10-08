/* D3D12 smoke: create device at each FL, queue, fence, allocator, cmdlist. Writes C:\d3d12probe.txt.
 * Build: tests/build-d3d12probe.sh */
#define COBJMACROS
#include <windows.h>
#include <d3d12.h>
#include <stdio.h>

static FILE *out;
#define LOG(...) do { fprintf(out, __VA_ARGS__); fputc('\n', out); fflush(out); } while (0)

int main(void)
{
    static const struct { D3D_FEATURE_LEVEL fl; const char *n; } fls[] = {
        { D3D_FEATURE_LEVEL_11_0, "11_0" }, { D3D_FEATURE_LEVEL_12_0, "12_0" },
        { D3D_FEATURE_LEVEL_12_1, "12_1" }, { D3D_FEATURE_LEVEL_12_2, "12_2" } };
    HRESULT (WINAPI *create)(IUnknown *, D3D_FEATURE_LEVEL, REFIID, void **);
    ID3D12Device *dev = NULL;
    HMODULE m;
    HRESULT hr;
    int i;

    out = fopen("C:\\d3d12probe.txt", "w");
    if (!out) return 2;
    m = LoadLibraryA("d3d12.dll");
    LOG("d3d12.dll %p", (void *)m);
    create = (void *)GetProcAddress(m, "D3D12CreateDevice");
    if (!create) { LOG("no D3D12CreateDevice"); return 1; }
    for (i = 0; i < 4; i++) {
        ID3D12Device *d = NULL;
        hr = create(NULL, fls[i].fl, &IID_ID3D12Device, (void **)&d);
        LOG("CreateDevice FL%s hr=0x%08lx", fls[i].n, (unsigned long)hr);
        if (SUCCEEDED(hr) && !dev) dev = d;
        else if (d) ID3D12Device_Release(d);
    }
    if (!dev) { LOG("FAIL no device"); return 1; }
    {
        D3D12_COMMAND_QUEUE_DESC qd = { D3D12_COMMAND_LIST_TYPE_DIRECT };
        ID3D12CommandQueue *q = NULL;
        ID3D12CommandAllocator *a = NULL;
        ID3D12GraphicsCommandList *l = NULL;
        ID3D12Fence *f = NULL;
        hr = ID3D12Device_CreateCommandQueue(dev, &qd, &IID_ID3D12CommandQueue, (void **)&q);
        LOG("CreateCommandQueue hr=0x%08lx", (unsigned long)hr);
        hr = ID3D12Device_CreateCommandAllocator(dev, D3D12_COMMAND_LIST_TYPE_DIRECT, &IID_ID3D12CommandAllocator, (void **)&a);
        LOG("CreateCommandAllocator hr=0x%08lx", (unsigned long)hr);
        hr = ID3D12Device_CreateCommandList(dev, 0, D3D12_COMMAND_LIST_TYPE_DIRECT, a, NULL, &IID_ID3D12GraphicsCommandList, (void **)&l);
        LOG("CreateCommandList hr=0x%08lx", (unsigned long)hr);
        hr = ID3D12Device_CreateFence(dev, 0, D3D12_FENCE_FLAG_NONE, &IID_ID3D12Fence, (void **)&f);
        LOG("CreateFence hr=0x%08lx", (unsigned long)hr);
        if (q && l && f) {
            ID3D12CommandList *lists[1];
            ID3D12GraphicsCommandList_Close(l);
            lists[0] = (ID3D12CommandList *)l;
            ID3D12CommandQueue_ExecuteCommandLists(q, 1, lists);
            ID3D12CommandQueue_Signal(q, f, 1);
            for (i = 0; i < 500 && ID3D12Fence_GetCompletedValue(f) < 1; i++) Sleep(10);
            LOG("fence completed=%llu", (unsigned long long)ID3D12Fence_GetCompletedValue(f));
        }
    }
    LOG("DONE");
    return 0;
}
