#pragma once
#include "tgcalls/platform/android/AndroidInterface.h"
#include "sdk/android/src/jni/android_network_monitor.h"

namespace tgcalls {
// Use the pinned WebRTC network monitor rather than BasicNetworkManager polling alone.
// ContextUtils has already been initialized with the application context by TgCallsBridge.
class DefaultAndroidInterface final : public AndroidInterface {
public:
    std::unique_ptr<rtc::NetworkMonitorFactory> createNetworkMonitorFactory() override {
        return std::make_unique<webrtc::jni::AndroidNetworkMonitorFactory>();
    }
};
}
