# Video to MP3

Android app (Android 10+). Pick a video from your gallery, or share one to the app,
and it saves the audio as an MP3 in **Music › Video to MP3**.

**Enhance module** (switch it on or off in the app) makes a second version,
"<name> (enhanced).mp3", with any mix of:
- Noise reduction (strength slider): learns the background noise from the quiet parts and removes it
- Voice clarity: cuts rumble and boominess, adds presence
- Reverb (amount slider): room or hall ambience
- Normalize volume: makes it as loud as possible without distortion

Each file has Play and Share buttons. You can choose MP3 quality: 128, 192 or 320 kbps.

## Build the APK

### Option A: Android Studio
1. Install Android Studio and open this folder (File › Open).
2. Wait for Gradle sync to finish, then choose Build › Build App Bundle(s) / APK(s) › Build APK(s).
3. The APK is in `app/build/outputs/apk/`. Copy it to your phone and tap it to install.

### Option B: GitHub (no install needed)
1. Create a new GitHub repository and upload this folder's contents, including the hidden `.github` folder.
2. Open the **Actions** tab. The "Build APK" workflow runs on its own and takes about 3–5 minutes.
3. Open the finished run and download **VideoToMP3-apk**. It's a zip that contains the APK.

## Notes
- No permissions are needed. The system picker gives the app access to the video you choose.
- MP3 encoding uses jump3r, a pure-Java port of LAME (LGPL, see LAME-LICENSE.txt), so no native libraries are needed.
- Everything runs on the phone, with nothing uploaded. A 10-minute video usually takes 1–3 minutes, depending on the phone.
