#include <jni.h>
#include <atomic>
#include <algorithm>
#include <array>
#include <map>
#include <mutex>
#include <thread>
#include "tgcalls/Instance.h"
#include "tgcalls/v2/InstanceV2ReferenceImpl.h"
#include "json11.hpp"
#include "rtc_base/logging.h"
#include "rtc_base/ssl_adapter.h"
#include "modules/utility/include/jvm_android.h"
#include "sdk/android/native_api/base/init.h"
#include "sdk/android/native_api/jni/jvm.h"

namespace {
JavaVM *javaVm = nullptr;
std::once_flag initialized;
std::mutex registryMutex;
std::atomic<jlong> nextId{1};
std::string string(JNIEnv *env, jstring value) {
    if (!value) return {};
    auto chars = env->GetStringUTFChars(value, nullptr);
    if (!chars) return {};
    std::string result(chars); env->ReleaseStringUTFChars(value, chars); return result;
}
struct Session {
    jlong id = 0;
    jobject listener = nullptr;
    jmethodID stateMethod = nullptr, signalMethod = nullptr;
    std::mutex mutex;
    bool closing = false;
    std::unique_ptr<tgcalls::Instance> instance;
    ~Session() { if (listener) webrtc::AttachCurrentThreadIfNeeded()->DeleteGlobalRef(listener); }
};
std::map<jlong, std::shared_ptr<Session>> registry;
std::shared_ptr<Session> find(jlong id) {
    std::lock_guard<std::mutex> lock(registryMutex);
    auto it = registry.find(id); return it == registry.end() ? nullptr : it->second;
}
void state(const std::weak_ptr<Session> &weak, tgcalls::State value) {
    auto session = weak.lock(); if (!session) return;
    auto env = webrtc::AttachCurrentThreadIfNeeded();
    env->CallVoidMethod(session->listener, session->stateMethod, session->id, static_cast<jint>(value));
    if (env->ExceptionCheck()) env->ExceptionClear();
}
void signal(const std::weak_ptr<Session> &weak, const std::vector<uint8_t> &data) {
    auto session = weak.lock(); if (!session || data.size() > 1024 * 1024) return;
    auto env = webrtc::AttachCurrentThreadIfNeeded();
    auto bytes = env->NewByteArray(static_cast<jsize>(data.size())); if (!bytes) return;
    env->SetByteArrayRegion(bytes, 0, data.size(), reinterpret_cast<const jbyte *>(data.data()));
    env->CallVoidMethod(session->listener, session->signalMethod, session->id, bytes);
    if (env->ExceptionCheck()) env->ExceptionClear();
    env->DeleteLocalRef(bytes);
}
void initialize() {
    std::call_once(initialized, [] {
        webrtc::InitAndroid(javaVm);
        webrtc::JVM::Initialize(javaVm);
        rtc::InitializeSSL();
        rtc::LogMessage::LogToDebug(rtc::LS_NONE);
        tgcalls::SetLoggingFunction([](const std::string &) {});
        tgcalls::Register<tgcalls::InstanceV2ReferenceImpl>();
    });
}
}
extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *) { javaVm = vm; return JNI_VERSION_1_6; }
extern "C" JNIEXPORT jstring JNICALL Java_com_vector_messenger_calling_TgCallsBridge_nativeCapabilities(JNIEnv *env, jobject) {
    initialize();
    json11::Json::array versions; for (const auto &version : tgcalls::Meta::Versions()) versions.push_back(version);
    // This bridge only registers the v2 engine. Advertise its layer, not legacy protocols.
    auto json = json11::Json(json11::Json::object{{"versions", versions}, {"layer", tgcalls::Meta::MaxLayer()}}).dump();
    return env->NewStringUTF(json.c_str());
}
extern "C" JNIEXPORT jlong JNICALL Java_com_vector_messenger_calling_TgCallsBridge_nativeCreate(JNIEnv *env, jobject,
    jstring versionValue, jbyteArray keyValue, jstring optionsValue, jobject listener) {
    initialize();
    if (!keyValue || env->GetArrayLength(keyValue) != 256 || !listener) return 0;
    std::string error;
    const auto options = json11::Json::parse(string(env, optionsValue), error);
    if (!error.empty()) return 0;
    auto key = std::make_shared<std::array<uint8_t, 256>>();
    env->GetByteArrayRegion(keyValue, 0, 256, reinterpret_cast<jbyte *>(key->data()));
    if (env->ExceptionCheck()) return 0;
    auto session = std::make_shared<Session>(); session->id = nextId++;
    session->listener = env->NewGlobalRef(listener);
    auto type = env->GetObjectClass(listener);
    session->stateMethod = env->GetMethodID(type, "onNativeState", "(JI)V");
    session->signalMethod = env->GetMethodID(type, "onNativeSignal", "(J[B)V");
    env->DeleteLocalRef(type);
    if (env->ExceptionCheck()) return 0;
    tgcalls::Descriptor descriptor{.encryptionKey = tgcalls::EncryptionKey(key, options["outgoing"].bool_value())};
    descriptor.version = string(env, versionValue);
    descriptor.config.initializationTimeout = options["connectTimeout"].number_value();
    descriptor.config.receiveTimeout = options["packetTimeout"].number_value();
    descriptor.config.enableP2P = options["p2p"].bool_value();
    descriptor.config.allowTCP = true;
    descriptor.config.enableAEC = true; descriptor.config.enableNS = true; descriptor.config.enableAGC = true;
    descriptor.config.maxApiLayer = options["layer"].int_value();
    descriptor.config.customParameters = options["custom"].string_value();
    descriptor.initialNetworkType = tgcalls::NetworkType::WiFi;
    std::vector<int64_t> reflectors;
    for (const auto &server : options["servers"].array_items()) if (server["reflector"].bool_value()) reflectors.push_back(std::stoll(server["id"].string_value()));
    std::sort(reflectors.begin(), reflectors.end());
    for (const auto &server : options["servers"].array_items()) {
        tgcalls::RtcServer rtc;
        rtc.host = server["host"].string_value(); rtc.port = server["port"].int_value();
        rtc.isTcp = server["tcp"].bool_value(); rtc.isTurn = server["turn"].bool_value();
        rtc.login = server["username"].string_value(); rtc.password = server["password"].string_value();
        if (server["reflector"].bool_value()) {
            const auto id = std::stoll(server["id"].string_value());
            rtc.id = std::find(reflectors.begin(), reflectors.end(), id) - reflectors.begin() + 1;
        } else rtc.id = static_cast<uint8_t>(std::stoll(server["id"].string_value()));
        descriptor.rtcServers.push_back(std::move(rtc));
    }
    auto weak = std::weak_ptr<Session>(session);
    descriptor.stateUpdated = [weak](tgcalls::State value) { state(weak, value); };
    descriptor.signalingDataEmitted = [weak](const std::vector<uint8_t> &value) { signal(weak, value); };
    descriptor.signalBarsUpdated = [](int) {};
    descriptor.remoteMediaStateUpdated = [](tgcalls::AudioState, tgcalls::VideoState) {};
    {
        std::lock_guard<std::mutex> lock(registryMutex); registry[session->id] = session;
    }
    session->instance = tgcalls::Meta::Create(descriptor.version, std::move(descriptor));
    rtc::LogMessage::LogToDebug(rtc::LS_NONE);
    if (!session->instance) { std::lock_guard<std::mutex> lock(registryMutex); registry.erase(session->id); return 0; }
    return session->id;
}
extern "C" JNIEXPORT void JNICALL Java_com_vector_messenger_calling_TgCallsBridge_nativeMute(JNIEnv *, jobject, jlong id, jboolean muted) {
    auto session = find(id); if (!session) return;
    std::lock_guard<std::mutex> lock(session->mutex);
    if (!session->closing && session->instance) session->instance->setMuteMicrophone(muted);
}
extern "C" JNIEXPORT void JNICALL Java_com_vector_messenger_calling_TgCallsBridge_nativeSignal(JNIEnv *env, jobject, jlong id, jbyteArray value) {
    auto session = find(id); if (!session || !value || env->GetArrayLength(value) > 1024 * 1024) return;
    std::vector<uint8_t> data(env->GetArrayLength(value));
    env->GetByteArrayRegion(value, 0, data.size(), reinterpret_cast<jbyte *>(data.data()));
    std::lock_guard<std::mutex> lock(session->mutex);
    if (!session->closing && session->instance) session->instance->receiveSignalingData(data);
}
extern "C" JNIEXPORT void JNICALL Java_com_vector_messenger_calling_TgCallsBridge_nativeStop(JNIEnv *, jobject, jlong id) {
    auto session = find(id); if (!session) return;
    { std::lock_guard<std::mutex> lock(registryMutex); registry.erase(id); }
    std::lock_guard<std::mutex> lock(session->mutex);
    if (session->closing || !session->instance) return;
    session->closing = true;
    session->instance->stop([session](tgcalls::FinalState) {
        // Destroy away from engine threads, after its stop completion. No audio/state is persisted.
        std::thread([session] { std::lock_guard<std::mutex> lock(session->mutex); session->instance.reset(); }).detach();
    });
}
