# Default sound assets

Seven original, procedurally synthesized mono PCM WAVs are included in app/src/main/res/raw:
- default_send.wav (130 ms), default_receive.wav (180 ms), default_error.wav (160 ms).
- default_menu.wav (100 ms), default_module.wav (100 ms), default_confirm.wav (150 ms).
- default_notification.wav (380 ms), used only as the default of newly created Android notification channels.

All waveforms are original to this project, contain no third-party samples, and are dedicated under CC0-1.0. scripts/Generate-DefaultSounds.py regenerates them using Python's standard library.

Message/send/receive sounds default on; failed-message and interface sounds default off. In-app volume defaults to 45%. Existing appearance preferences storage persists all controls. SoundPool creation/loading/playback/release runs on one worker; samples are reused while foregrounded and released on Activity stop. No audio focus is requested. Silent/vibrate, DND, active media/calls and recording suppress in-app cues. Unloaded/stale cues are dropped instead of replayed later; rapid cues cannot overlap.

Accepted text/photo/voice/retry sends use repository feedback with actual message IDs. Status changes never replay acceptance sounds. Failed-message feedback uses actual failed TDLib messages; validation errors and uncertain sends do not play an error/success tone. Incoming cues require new-message events, the visible conversation, Ready connection state, and a fresh message timestamp; history/backlog loads have no soundtrack. Confirmations follow successful edit/pin/archive operations. Menu/module cues follow changes, not pointer frames.

Android owns notification sound, volume, DND and per-chat notification policy. Existing channels keep their sound choices. Settings > Sounds links directly to Messages/Groups/Mentions channel settings; users of existing installations can select their preferred tone there. No SoundPool notification alert is played in the background.

Device listening tests remain necessary for tone balance, silent/DND modes, media coexistence, asynchronous first-load behavior and event deduplication. No messages need be sent by automated verification.
