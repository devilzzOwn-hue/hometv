# Home TV for Fire TV

A Fire TV app with a cleaner interface for your Stalker/MAG IPTV portal (the one you use in StbEmu). It has instant search, favourites, recently watched, a now/next guide, Movies & Shows, and full remote-control support. Everything runs on the Fire Stick, so you don't need a computer.

Your portal login is **not** stored in this code. You enter it once on the TV, and the Settings screen lets you do that from your phone.

## 1. Build the APK (one time, free, about 5 minutes)
GitHub builds the app with Google's official Android tools.

1. Sign in at github.com and create a **new public repository** called `hometv`. Leave it empty: no README, no .gitignore.
2. On the empty repo page, click **"uploading an existing file"**. Drag in **everything inside this folder, including the hidden `.github` folder**, then click **Commit changes**.
   - Mac: press Cmd+Shift+. in Finder to show hidden folders. Windows: View → Show → Hidden items.
   - If dragging the `.github` folder doesn't work, create the file `.github/workflows/build.yml` with **Add file → Create new file** and paste in its contents.
3. Open the **Actions** tab. The "Build Fire TV app" run starts by itself and takes about 4 minutes. If GitHub asks you to enable workflows, click the button.
4. When it shows a green tick, the APK is at:
   `https://github.com/YOUR-USERNAME/hometv/releases/latest/download/HomeTV.apk`

## 2. Install on the Fire Stick
1. Fire TV **Settings → My Fire TV → Developer options → Install unknown apps**. Turn it on for **Downloader**.
   (If Developer options is missing: Settings → My Fire TV → About → click the device name 7 times.)
2. Open **Downloader**, type the link from step 1.4 and press Go. When it finishes, choose **Install**, then **Done**. You can delete the downloaded file afterwards to save space.
3. **Home TV** now appears in Your Apps. You can move it to the home row.

## 3. First launch
Home TV opens the Settings screen. It shows an address like `http://192.168.1.42:8765/setup`. Open that on your phone (on the same Wi-Fi) and paste in your details:
- Portal URL, MAC address, STB model (MAG424), serial number, Device ID

Tap **Save & connect**. When it says "Connected", press **Continue** on the TV.

## Remote control
| Button | Does |
|---|---|
| Arrows / OK | Move and open |
| Back | Close the player, clear the search, then press twice to exit |
| ☰ Menu | Add or remove the favourite (on a channel tile or while watching) |
| Up / Down while watching | Previous / next channel |
| ⏯ | Play / pause (movies) |

## Updating later
Edit any file on GitHub and commit. Actions builds a new APK at the same link. Install it with Downloader again; your settings and favourites stay.

## Notes
- **One stream at a time** is on by default, so a single-screen plan won't get flagged. Turn it off in Settings if your plan allows more screens.
- Each Fire Stick keeps its own favourites.
- The signing key in `keystore/` only lets updates install over the old version. It is not a secret that protects your account.
