# Activation panel setup

How it works: each TV shows a **Device ID** (a MAC-style address) and stays locked.
You open your panel, find that ID, assign a playlist link and an expiry date, and the TV unlocks
within about 20 seconds. When the expiry date passes, or you disable the device, the TV locks again.

Note on the MAC address: Android 10 and newer do not let apps read the real hardware MAC.
The app creates its own stable MAC-style ID for each TV instead. It stays the same after restarts and updates.
It changes only if the app is uninstalled and the TV's data is cleared, or the TV is factory reset.

## 1. Create the database (Supabase, free)

1. Sign up at supabase.com and create a project. Save the database password.
2. Open **SQL Editor > New query**, paste the whole of `panel/supabase-setup.sql`, press **Run**.
3. Open **Authentication > Sign In / Providers** (or Settings) and **turn off "Allow new users to sign up"**.
   This is important: it makes sure only you can sign in to the panel.
4. Open **Authentication > Users > Add user > Create new user**. Enter your email and a strong password,
   and tick **Auto Confirm User**. This is your panel login.
5. Open **Project Settings > API** and copy two values:
   - **Project URL**, for example `https://abcdxyz.supabase.co`
   - **anon / publishable key** (the public one). Never use the `service_role` key.

## 2. Publish the panel (GitHub Pages)

1. In your repository, open `docs/config.js`, press the pencil icon, paste the URL and the anon key between the quotes, and commit.
2. Open **Settings > Pages**. Under **Build and deployment**, choose **Deploy from a branch**,
   branch `main`, folder `/docs`, and save.
3. After a minute your panel is live at `https://YOUR-USERNAME.github.io/YOUR-REPO/`. Sign in with the user from step 1.4.

## 3. Connect the TV app to the same database

1. In your repository open **Settings > Secrets and variables > Actions > New repository secret** and add two secrets:
   - `SUPABASE_URL` with the Project URL
   - `SUPABASE_ANON_KEY` with the anon key
2. Open **Actions > Build APK > Run workflow**. The new APK on the Releases page now requires activation.
   (An APK built without these secrets is a plain player with no activation.)

## How clients sign in

When the TV app opens and the device is not activated, the client sees the Device ID and up to three ways in:

| Option | What the client does | What you do |
| --- | --- | --- |
| **Activation code** | Types the code you gave them | Panel > Codes > Generate codes, send the code |
| **M3U link** | Types their own M3U link | Nothing |
| **Xtream login** | Types server, username and password | Nothing |

Or skip all of that and activate the TV yourself by its Device ID (see below).
In **Settings** you can turn off "Allow clients to sign in with their own M3U link or Xtream login".
Then only activation codes and devices you activate will work.

An activation or code always wins. If a client signs in with their own playlist and later enters a code
or you activate the device, the app switches to your source.

## Panel tabs

- **Sources**: your library of playlists. Add an **M3U link**, or an **Xtream login** (server, username, password; the app builds the link for you). Add each once, then reuse it.
- **Codes**: generate single-use codes (1 to 100 at a time). Pick a source, the number of days (or no expiry), and an optional note such as the client's name.
  A code that is used on a TV that still has time left adds its days on top.
- **Devices**: every TV that has opened the app. Edit a row to pick a source (or type a custom link), set the expiry, or disable it.
- **Settings**: allow or block clients using their own M3U / Xtream.

## Activating a client by Device ID

1. Client installs the app with Downloader and opens it. The screen shows the Device ID.
2. Client sends you the ID, as text or as a photo or screenshot of the screen. The ID also appears in Devices by itself as **Pending**.
3. Press **Edit** on that row (or **Add device** and type the ID from the photo; if the ID is already listed, the panel opens that row).
   Enter the client name, then either choose a source from your library or fill in **Custom login**:
   an M3U link, or an Xtream server, username and password. Set the expiry date and save.
4. The TV unlocks within 20 seconds, or at once when the client presses **Check again**.

To renew, press Edit and add months. To block a client, untick **Enabled**.
If the TV has no internet, it keeps working from its last confirmed answer until the expiry date.

## Upgrading from the first version

If you already ran the earlier `supabase-setup.sql`, run the new one again. It is safe to repeat and keeps your devices.
Then replace the files in your repository with the new ones and run **Build APK** again.

## Security notes

- Anyone who knows a Device ID could ask the server for that device's playlist link. Device IDs are not guessable,
  but treat playlist links as private anyway.
- Activation codes are 12 characters long and single use, so they cannot be guessed in practice. Delete unused codes you no longer need.
- Xtream passwords you store in Sources are visible to you in the panel and are sent to the TVs you assign them to.
- The panel page is public, but nobody can read or change data without your login.
  Use a strong password and keep sign-ups switched off.
- Because the APK is signed with the key in `app/release.keystore`, replace that key with your own
  before distributing the app widely.
