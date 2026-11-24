# Upload this project to GitHub (browser-only)

1. Sign in to GitHub and choose **New repository**.
2. Name it `logibit-universal-print`. Choose Public or Private as you prefer. Do not initialize it with a README (this folder already has one).
3. Create the repository.
4. Choose **Add file → Upload files**.
5. Open the extracted `logibit-universal-print` folder on your computer and drag all its contents (including the hidden `.github` folder) into GitHub's upload area. If your browser does not show hidden folders, enable showing hidden files or upload the `.github/workflows` files separately using **Add file → Create new file**.
6. Commit the upload to `main`.
7. Open **Actions** and allow workflows if GitHub asks. Wait for `Android APK` to finish.
8. Open the completed run, scroll to **Artifacts**, and download `logibit-universal-print-debug-apk`.

The APK is produced by GitHub-hosted build machines. Android Studio is not needed.
