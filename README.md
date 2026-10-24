# 無音カメラ (Silent Camera)

シャッター音・録画開始/停止音を鳴らさずに写真と動画を撮る Android アプリ。Galaxy Z Fold 7 向けに作成。

- 写真／動画の撮影（CameraX。シャッター音を再生しない）
- フラッシュ切替、前後カメラ切替、ピンチズーム、タップでピント合わせ
- 動画の音声（マイク）録音のオン／オフ
- 撮影した写真・動画の一覧とプレビュー（スワイプで切替、動画は再生可）
- 保存先：`DCIM/SilentCamera`
- 設定画面の「更新を確認」で最新版に更新

## インストール

[最新リリース](https://github.com/rebuildon0/silent-camera/releases/latest) から `silent-camera.apk` をダウンロードして開く。

## ビルド

`main` に push すると GitHub Actions が署名付き APK をビルドし、Release（`silent-camera.apk` と `version.json`）を公開する。
アプリは `releases/latest/download/version.json` を見て更新を判定する。

署名鍵は Secrets（`KEYSTORE_B64`, `KEYSTORE_PASSWORD`）にあり、リポジトリには含めない。鍵を変えると既存アプリへの上書き更新ができなくなる。
