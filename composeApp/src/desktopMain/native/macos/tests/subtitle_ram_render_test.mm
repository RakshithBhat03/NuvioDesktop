// Offscreen verification of the rebuilt libmpv renderer and VideoToolbox path.
#include <mpv/client.h>
#include <mpv/render_gl.h>
#include <OpenGL/OpenGL.h>
#include <OpenGL/gl3.h>
#include <dlfcn.h>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <thread>
#include <string>
#include <vector>

static void require(bool ok, const char *message) {
    if (!ok) { std::fprintf(stderr, "%s\n", message); std::exit(1); }
}
static void *resolve(void *, const char *name) { return dlsym(RTLD_DEFAULT, name); }

int main(int argc, char **argv) {
    require(argc == 2, "usage: render-test MEDIA");
    CGLPixelFormatAttribute attributes[] = {
        kCGLPFAOpenGLProfile, (CGLPixelFormatAttribute)kCGLOGLPVersion_3_2_Core,
        kCGLPFAAccelerated, (CGLPixelFormatAttribute)0,
    };
    CGLPixelFormatObj format = nullptr;
    GLint count = 0;
    require(CGLChoosePixelFormat(attributes, &format, &count) == kCGLNoError, "Pixel format");
    CGLContextObj gl = nullptr;
    require(CGLCreateContext(format, nullptr, &gl) == kCGLNoError, "OpenGL context");
    CGLDestroyPixelFormat(format);
    CGLSetCurrentContext(gl);
    GLuint texture, framebuffer;
    glGenTextures(1, &texture);
    glBindTexture(GL_TEXTURE_2D, texture);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 320, 180, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
    glGenFramebuffers(1, &framebuffer);
    glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0);
    require(glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE, "Framebuffer");
    mpv_handle *player = mpv_create();
    require(player, "mpv_create");
    for (const char *name : {"config", "terminal"}) mpv_set_option_string(player, name, "no");
    mpv_set_option_string(player, "ao", "null");
    mpv_set_option_string(player, "vo", "libmpv");
    mpv_set_option_string(player, "hwdec", "videotoolbox");
    mpv_set_option_string(player, "sid", "1");
    require(mpv_set_option_string(player, "demuxer-subtitle-cache-bytes", "32MiB") >= 0, "RAM cache option");
    require(mpv_initialize(player) >= 0, "mpv_initialize");
    mpv_opengl_init_params init{resolve, nullptr};
    mpv_render_param params[] = {{MPV_RENDER_PARAM_API_TYPE, (void *)MPV_RENDER_API_TYPE_OPENGL},
                                {MPV_RENDER_PARAM_OPENGL_INIT_PARAMS, &init}, {MPV_RENDER_PARAM_INVALID, nullptr}};
    mpv_render_context *renderer = nullptr;
    require(mpv_render_context_create(&renderer, player, params) >= 0, "mpv OpenGL renderer");
    const char *command[] = {"loadfile", argv[1], nullptr};
    require(mpv_command(player, command) >= 0, "loadfile");
    bool switched = false;
    int frames = 0;
    for (int i = 0; i < 1200; i++) {
        double position = 0;
        mpv_get_property(player, "time-pos", MPV_FORMAT_DOUBLE, &position);
        if (!switched && position > 2) {
            mpv_set_property_string(player, "sid", "2");
            switched = true;
        }
        if (mpv_render_context_update(renderer) & MPV_RENDER_UPDATE_FRAME) {
            mpv_opengl_fbo fbo{(int)framebuffer, 320, 180, GL_RGBA8};
            mpv_render_param renderParams[] = {{MPV_RENDER_PARAM_OPENGL_FBO, &fbo}, {MPV_RENDER_PARAM_INVALID, nullptr}};
            require(mpv_render_context_render(renderer, renderParams) >= 0, "Render frame");
            frames++;
        }
        if (position > 4) break;
        std::this_thread::sleep_for(std::chrono::milliseconds(10));
    }
    require(switched && frames > 30, "Playback must render frames across the subtitle switch");
    std::vector<unsigned char> pixels(320 * 180 * 4);
    glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
    glReadPixels(0, 0, 320, 180, GL_RGBA, GL_UNSIGNED_BYTE, pixels.data());
    int colored = 0;
    for (size_t i = 0; i < pixels.size(); i += 4)
        colored += pixels[i] > 25 || pixels[i+1] > 25 || pixels[i+2] > 25;
    require(colored > 1000, "Video must not be black");
    char *hardware = mpv_get_property_string(player, "hwdec-current");
    std::printf("RENDER frames=%d colored_pixels=%d hwdec=%s\n", frames, colored, hardware ? hardware : "unavailable");
    require(hardware && std::string(hardware) == "videotoolbox", "Hardware decoding must remain available");
    mpv_free(hardware);
    mpv_render_context_free(renderer);
    mpv_terminate_destroy(player);
    glDeleteFramebuffers(1, &framebuffer);
    glDeleteTextures(1, &texture);
    CGLSetCurrentContext(nullptr);
    CGLDestroyContext(gl);
}
