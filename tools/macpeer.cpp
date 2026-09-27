// Drives the REAL macOS AeroServer / CertManager sources, unmodified, so the
// Android side can be tested against the actual C++ implementation.
#include "AeroServer.h"
#include <cstdio>
#include <cstring>
#include <string>
#include <thread>
#include <chrono>
#include <signal.h>
#include <unistd.h>

static volatile bool g_quit  = false;
static volatile bool g_done = false;
static volatile int  g_ok   = 0;
static void on_sig(int) { g_quit = true; }

int main(int argc, char** argv) {
    // argv[1] = mode ("recv" | "send"), argv[2] = port
    //           recv: argv[3] = download dir
    //           send: argv[3] = file to send, argv[4] = host
    std::string mode = argc > 1 ? argv[1] : "recv";
    int port         = argc > 2 ? atoi(argv[2]) : 7771;
    signal(SIGINT, on_sig);
    signal(SIGTERM, on_sig);

    AeroServer server;
    server.setDownloadDirectory(argc > 3 ? argv[3] : "/tmp/aerointerop/dl");

    if (mode == "recv") {
        if (!server.start(port)) { fprintf(stderr, "start failed\n"); return 1; }
        fprintf(stderr, "READY\n"); fflush(stderr);
        while (!g_quit) std::this_thread::sleep_for(std::chrono::milliseconds(100));
        server.stop();
        return 0;
    } else {
        // send mode: act as the Mac pushing to a peer (the Android device)
        std::string file = argc > 3 ? argv[3] : "";
        std::string host = argc > 4 ? argv[4] : "127.0.0.1";
        server.sendFile(file, host, port,
            [](const TransferProgress& p) {
                fprintf(stderr, "progress %s %llu/%llu\n", p.filename.c_str(),
                        (unsigned long long)p.bytes_transferred,
                        (unsigned long long)p.total_bytes);
            },
            [](bool ok, const std::string& err) {
                fprintf(stderr, "DONE %s %s\n", ok ? "OK" : "FAIL", err.c_str());
                fflush(stderr);
                // Record and let the worker finish its teardown. Calling
                // _exit() here would kill the process inside the callback,
                // before sendFile drains the socket and closes it cleanly.
                g_ok   = ok ? 1 : 0;
                g_done = true;
            });

        while (!g_done && !g_quit) std::this_thread::sleep_for(std::chrono::milliseconds(50));
        // Give sendFile a moment to run its drain/close path before we exit.
        std::this_thread::sleep_for(std::chrono::milliseconds(300));
        return g_ok;
    }
}
