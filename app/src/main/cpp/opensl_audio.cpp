// OpenSL ES audio backend for SNES Deco.
//
// AudioTrack proved unreliable on the user's device (reported PLAYING but
// silent). OpenSL ES talks to the platform audio engine directly and is the
// more robust path on Android for low-latency streamed PCM. We expose a tiny
// C API: init(sampleRate), enqueue(samples), shutdown().

#include <SLES/OpenSLES.h>
#include <SLES/OpenSLES_Android.h>
#include <vector>
#include <mutex>
#include <cstring>
#include <android/log.h>

#define ALOG(...) __android_log_print(ANDROID_LOG_INFO, "SNESDecoAudio", __VA_ARGS__)

namespace {

SLObjectItf engineObj = nullptr;
SLEngineItf engine = nullptr;
SLObjectItf outputMixObj = nullptr;
SLObjectItf playerObj = nullptr;
SLPlayItf   play = nullptr;
SLAndroidSimpleBufferQueueItf bufQueue = nullptr;

// Double buffer of interleaved stereo int16 samples.
std::mutex ringMutex;
std::vector<int16_t> pending;   // samples waiting to be sent
std::vector<int16_t> playing;   // buffer currently owned by OpenSL
bool running = false;

void bufferCallback(SLAndroidSimpleBufferQueueItf bq, void *) {
    std::lock_guard<std::mutex> lock(ringMutex);
    if (!running) return;
    playing.clear();
    if (!pending.empty()) {
        playing.swap(pending);
    } else {
        // Nothing queued: play a small chunk of silence to keep the queue fed.
        playing.assign(1024 * 2, 0);
    }
    (*bq)->Enqueue(bq, playing.data(), playing.size() * sizeof(int16_t));
}

} // namespace

extern "C" {

bool SndOpenSLInit(int sampleRate) {
    if (engineObj) return true; // already initialized

    if (slCreateEngine(&engineObj, 0, nullptr, 0, nullptr, nullptr) != SL_RESULT_SUCCESS) return false;
    if ((*engineObj)->Realize(engineObj, SL_BOOLEAN_FALSE) != SL_RESULT_SUCCESS) return false;
    if ((*engineObj)->GetInterface(engineObj, SL_IID_ENGINE, &engine) != SL_RESULT_SUCCESS) return false;

    if ((*engine)->CreateOutputMix(engine, &outputMixObj, 0, nullptr, nullptr) != SL_RESULT_SUCCESS) return false;
    if ((*outputMixObj)->Realize(outputMixObj, SL_BOOLEAN_FALSE) != SL_RESULT_SUCCESS) return false;

    SLDataLocator_AndroidSimpleBufferQueue locBufQ = {
        SL_DATALOCATOR_ANDROIDSIMPLEBUFFERQUEUE, 2
    };
    SLDataFormat_PCM formatPcm = {
        SL_DATAFORMAT_PCM,
        2,                                  // stereo
        (SLuint32) sampleRate * 1000,       // milliHz
        SL_PCMSAMPLEFORMAT_FIXED_16,
        SL_PCMSAMPLEFORMAT_FIXED_16,
        SL_SPEAKER_FRONT_LEFT | SL_SPEAKER_FRONT_RIGHT,
        SL_BYTEORDER_LITTLEENDIAN
    };
    SLDataSource audioSrc = { &locBufQ, &formatPcm };

    SLDataLocator_OutputMix locOutMix = { SL_DATALOCATOR_OUTPUTMIX, outputMixObj };
    SLDataSink audioSnk = { &locOutMix, nullptr };

    const SLInterfaceID ids[1] = { SL_IID_ANDROIDSIMPLEBUFFERQUEUE };
    const SLboolean req[1] = { SL_BOOLEAN_TRUE };
    if ((*engine)->CreateAudioPlayer(engine, &playerObj, &audioSrc, &audioSnk, 1, ids, req) != SL_RESULT_SUCCESS) return false;
    if ((*playerObj)->Realize(playerObj, SL_BOOLEAN_FALSE) != SL_RESULT_SUCCESS) return false;
    if ((*playerObj)->GetInterface(playerObj, SL_IID_PLAY, &play) != SL_RESULT_SUCCESS) return false;
    if ((*playerObj)->GetInterface(playerObj, SL_IID_ANDROIDSIMPLEBUFFERQUEUE, &bufQueue) != SL_RESULT_SUCCESS) return false;

    if ((*bufQueue)->RegisterCallback(bufQueue, bufferCallback, nullptr) != SL_RESULT_SUCCESS) return false;

    running = true;
    if ((*play)->SetPlayState(play, SL_PLAYSTATE_PLAYING) != SL_RESULT_SUCCESS) return false;

    // Prime the queue so the callback loop starts.
    {
        std::lock_guard<std::mutex> lock(ringMutex);
        playing.assign(1024 * 2, 0);
        (*bufQueue)->Enqueue(bufQueue, playing.data(), playing.size() * sizeof(int16_t));
    }
    ALOG("OpenSL ES initialized @ %d Hz", sampleRate);
    return true;
}

// Queue interleaved stereo int16 samples for playback.
static volatile int g_lastPeak = 0;
static volatile long g_totalEnqueued = 0;

void SndOpenSLEnqueue(const int16_t *samples, int count) {
    if (!running || count <= 0) return;
    // Track peak amplitude of what we actually receive, for diagnostics.
    int peak = 0;
    for (int i = 0; i < count; i++) {
        int a = samples[i] < 0 ? -samples[i] : samples[i];
        if (a > peak) peak = a;
    }
    g_lastPeak = peak;
    g_totalEnqueued += count;

    std::lock_guard<std::mutex> lock(ringMutex);
    // Cap backlog so we don't build unbounded latency if the game outpaces out.
    const size_t MAX_BACKLOG = 48000 * 2 / 2; // ~0.5s stereo
    if (pending.size() > MAX_BACKLOG) pending.clear();
    pending.insert(pending.end(), samples, samples + count);
}

int SndOpenSLLastPeak() { return g_lastPeak; }
long SndOpenSLTotalEnqueued() { return g_totalEnqueued; }

void SndOpenSLShutdown() {
    {
        std::lock_guard<std::mutex> lock(ringMutex);
        running = false;
        pending.clear();
    }
    if (playerObj) { (*playerObj)->Destroy(playerObj); playerObj = nullptr; play = nullptr; bufQueue = nullptr; }
    if (outputMixObj) { (*outputMixObj)->Destroy(outputMixObj); outputMixObj = nullptr; }
    if (engineObj) { (*engineObj)->Destroy(engineObj); engineObj = nullptr; engine = nullptr; }
}

} // extern "C"
