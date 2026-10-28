# Privacy Policy — DubStudio

_Last updated: 28 October 2026_

DubStudio is a video dubbing app for Android. This policy explains exactly what leaves
your device and why. It is deliberately short, because the app does very little.

## Who we are

DubStudio is an independent app published by Rajok. Contact:
[rajok8649@gmail.com](mailto:rajok8649@gmail.com).

## What the app does with your video

Your video **never leaves your device**. It is not uploaded, not backed up, and not sent
to the developer or to any analytics service.

To dub a video, the app does the following on the phone itself:

1. Reads the audio track out of the video file.
2. Cuts the audio into chunks of 26 seconds or less.
3. Sends **only those short audio chunks** to Sarvam AI for processing.

## What is sent to Sarvam AI

The app uses the [Sarvam AI](https://www.sarvam.ai) API, and only that service. Three
kinds of request are made:

| What is sent | Endpoint | Why |
|---|---|---|
| A mono audio chunk, 16 kHz, 26 seconds or less | `POST /speech-to-text` | To turn the speech into text |
| That text | `POST /translate` | To translate it into the language you chose |
| The translated text | `POST /text-to-speech` | To generate the new voice |

Requests go over HTTPS to `api.sarvam.ai`. No video frames, images, file names, contact
details or device identifiers are sent. Sarvam AI's own handling of this data is
governed by their privacy policy at <https://www.sarvam.ai/privacy-policy>.

The dubbed video is assembled on your phone and saved to your own storage. It is not
uploaded anywhere.

## Your API key

You supply your own Sarvam AI API key. It is stored in the app's private
`SharedPreferences` storage, readable only by this app, and is sent only as an
authentication header to `api.sarvam.ai`. It is never sent to the developer, and the
developer has no way to read it.

## What the app does not collect

- No accounts, no sign-in, no user profiles.
- No analytics, crash reporting, advertising or tracking SDKs.
- No location, contacts, calendar, camera, microphone, SMS, call logs or phone state.
- No access to your files beyond the single video you pick.

## Permissions

The app declares **one** Android permission:

- `android.permission.INTERNET` — required to reach the Sarvam AI API.

Choosing a video uses the system document picker (`ACTION_OPEN_DOCUMENT`), which grants
access to that one file and needs no storage permission. Saving the result uses
`MediaStore`, which also needs no permission.

## Children

The app is not directed at children under 13 and collects no personal data from anyone.

## Your control

- Delete any dubbed video from `Movies/DubStudio/` in your gallery, or from the app's
  storage.
- Clear the stored API key by reinstalling the app or clearing its data in Android
  Settings.
- Uninstalling removes everything the app stored.

## Changes

If this policy changes, the updated version will be published at this URL with a new
date.

## Contact

Questions about privacy: [rajok8649@gmail.com](mailto:rajok8649@gmail.com).
