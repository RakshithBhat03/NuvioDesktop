#import <Foundation/Foundation.h>
#include <sys/stat.h>
#include <mpv/client.h>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <string>
#include <thread>
#include <utility>

static void check(bool condition, const char *message) {
    if (!condition) {
        std::fprintf(stderr, "%s\n", message);
        std::exit(1);
    }
}

static double number(mpv_handle *mpv, const char *name) {
    double value = 0;
    mpv_get_property(mpv, name, MPV_FORMAT_DOUBLE, &value);
    return value;
}

static int64_t subtitleBytes(mpv_handle *mpv) {
    mpv_node state{};
    if (mpv_get_property(mpv, "demuxer-cache-state", MPV_FORMAT_NODE, &state) < 0)
        return 0; // unavailable after stop
    int64_t bytes = -1;
    check(state.format == MPV_FORMAT_NODE_MAP, "Cache state map");
    for (int i = 0; i < state.u.list->num; i++) {
        if (std::string(state.u.list->keys[i]) == "subtitle-cache-bytes")
            bytes = state.u.list->values[i].u.int64;
    }
    mpv_free_node_contents(&state);
    check(bytes >= 0, "Subtitle cache accounting must be exposed");
    return bytes;
}

static bool flag(mpv_handle *mpv, const char *name) {
    int value = 0;
    mpv_get_property(mpv, name, MPV_FORMAT_FLAG, &value);
    return value != 0;
}

static void command(mpv_handle *mpv, const char *a, const char *b, const char *c = nullptr) {
    const char *args[] = {a, b, c, nullptr};
    check(mpv_command(mpv, args) >= 0, a);
}

static void waitFor(mpv_handle *mpv, double position) {
    for (int i = 0; i < 1200; i++) {
        if (number(mpv, "time-pos") >= position) return;
        std::this_thread::sleep_for(std::chrono::milliseconds(10));
    }
    check(false, "Playback did not reach the expected position");
}

int main(int argc, char **argv) {
    @autoreleasepool {
        check(argc == 3, "usage: test URL baseline|cached");
        bool cached = std::string(argv[2]) != "baseline";
        bool streaming = std::string(argv[2]) == "streaming";
        bool capped = std::string(argv[2]) == "capped";
        mpv_handle *mpv = mpv_create();
        check(mpv != nullptr, "mpv_create");
        for (auto pair : {std::pair{"config", "no"}, {"vo", "null"}, {"ao", "null"},
             {"terminal", "no"}, {"idle", "yes"}, {"keep-open", "yes"}, {"sid", "1"},
             {"cache", "auto"}, {"cache-secs", "120"}, {"demuxer-max-bytes", "150MiB"},
             {"http-header-fields", "X-Nuvio-Test: subtitles"}}) {
            check(mpv_set_option_string(mpv, pair.first, pair.second) >= 0, pair.first);
        }
        check(mpv_set_option_string(mpv, "demuxer-subtitle-cache-bytes", cached ? (capped ? "1024" : "32MiB") : "0") >= 0,
              "Patched mpv subtitle cache option");
        if (streaming) {
            mpv_set_option_string(mpv, "demuxer-max-bytes", "1MiB");
            mpv_set_option_string(mpv, "cache-secs", "2");
        }
        check(mpv_initialize(mpv) >= 0, "mpv_initialize");
        NSString *source = [NSString stringWithUTF8String:argv[1]];
        command(mpv, "loadfile", source.UTF8String);
        waitFor(mpv, 6);
        check(streaming || flag(mpv, "demuxer-cache-idle"), "Fixture must be fully cached before the switch");
        check(streaming || number(mpv, "demuxer-cache-duration") > 20, "Expected a filled playback cache");
        // Catch open-but-unlinked caches too; a directory listing cannot.
        for (int fd = 0; fd < 1024; fd++) {
            struct stat info;
            if (fstat(fd, &info) == 0)
                check(!(S_ISREG(info.st_mode) && info.st_nlink == 0 && info.st_size > 0),
                      "No anonymous disk cache is allowed");
        }
        double retained = subtitleBytes(mpv);
        check(retained <= (capped ? 1024 : 32 * 1024 * 1024), "Subtitle RAM cap");
        check(!cached || retained > 0, "Cache must contain subtitle packets");
        std::puts("READY");
        std::fflush(stdout);
        std::getchar(); // Python records the server counters before releasing us.

        double before = number(mpv, "time-pos");
        auto start = std::chrono::steady_clock::now();
        check(mpv_set_property_string(mpv, "sid", "2") >= 0, "Select built-in subtitle 2");
        int loadingSamples = 0;
        for (int i = 0; i < 200; i++) {
            loadingSamples += flag(mpv, "paused-for-cache") ||
                (flag(mpv, "core-idle") && !flag(mpv, "pause") && !flag(mpv, "eof-reached"));
            std::this_thread::sleep_for(std::chrono::milliseconds(10));
        }
        double elapsed = std::chrono::duration<double>(std::chrono::steady_clock::now() - start).count();
        double progress = number(mpv, "time-pos") - before;
        std::printf("RESULT elapsed=%.3f progress=%.3f loading_samples=%d subtitle_bytes=%.0f\n", elapsed, progress, loadingSamples, retained);
        std::fflush(stdout);
        if (cached) {
            char *selectedText = mpv_get_property_string(mpv, "sub-text");
            check(capped || (selectedText && std::string(selectedText).find("Track 2 cue") != std::string::npos),
                  "Newly selected subtitle must actually render");
            mpv_free(selectedText);
            check(progress > elapsed - 0.25, "Cached subtitle switching must avoid the playback stall");
            check(loadingSamples < 20, "Cached switching must not trigger sustained buffering");
            check(number(mpv, "track-list/count") == 4, "No extra reader or duplicate tracks");
            if (!streaming) {
            check(mpv_set_property_string(mpv, "pause", "yes") >= 0, "Pause");
            check(mpv_set_property_string(mpv, "sid", "no") >= 0, "Subtitles off");
            check(mpv_set_property_string(mpv, "sid", "1") >= 0, "Switch back");
            check(flag(mpv, "pause"), "Preserve an explicit pause");
            command(mpv, "seek", "12", "absolute+exact");
            mpv_set_property_string(mpv, "pause", "no");
            waitFor(mpv, 12.3);
            char *text = mpv_get_property_string(mpv, "sub-text");
            check(capped || (text && std::string(text).find("Track 1 cue 12") != std::string::npos),
                  "Keep subtitles synchronized after switching and seeking");
            mpv_free(text);
            // Addon subtitles fetch only their own file, using the existing sub-add path.
            std::string addon = argv[1];
            addon = addon.substr(0, addon.rfind('/') + 1) + "external.srt";
            double addonStart = number(mpv, "time-pos");
            command(mpv, "sub-add", addon.c_str(), "select");
            bool sawExternal = false;
            for (int i = 0; i < 200; i++) {
                char *cue = mpv_get_property_string(mpv, "sub-text");
                sawExternal |= cue && std::string(cue).find("External cue") != std::string::npos;
                mpv_free(cue);
                std::this_thread::sleep_for(std::chrono::milliseconds(10));
            }
            check(sawExternal, "Addon subtitle must render");
            check(number(mpv, "time-pos") > addonStart + 1.5, "Addon download must preserve playback");
            command(mpv, "sub-remove", "3");
            check(mpv_set_property_string(mpv, "sid", "2") >= 0, "Return to built-in subtitles");
            bool sawBuiltIn = false;
            for (int i = 0; i < 150; i++) {
                char *cue = mpv_get_property_string(mpv, "sub-text");
                sawBuiltIn |= cue && std::string(cue).find("Track 2 cue") != std::string::npos;
                mpv_free(cue);
                std::this_thread::sleep_for(std::chrono::milliseconds(10));
            }
            check(capped || sawBuiltIn, "Built-in subtitle must render after removing addon");
            }
        } else {
            check(loadingSamples > 20, "Baseline must reproduce buffering");
        }
        command(mpv, "stop", nullptr);
        for (int i = 0; i < 100 && subtitleBytes(mpv) != 0; i++)
            std::this_thread::sleep_for(std::chrono::milliseconds(10));
        check(subtitleBytes(mpv) == 0,
              "Release subtitle cache on stream close");
        mpv_terminate_destroy(mpv);
    }
}
