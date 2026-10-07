This folder holds the data that makes ShortsGen work completely offline.

It is intentionally NOT committed to git (the files are ~70 MB, and GitHub's web
uploader rejects files larger than 25 MB).

The CI workflow (.github/workflows/android.yml) runs

    bash scripts/prepare_assets.sh

before building, which downloads from the Shorts-Gen release and puts here:

    font.ttf
    vits-piper-en_US-ljspeech-medium/en_US-ljspeech-medium.onnx
    vits-piper-en_US-ljspeech-medium/en_US-ljspeech-medium.onnx.json
    vits-piper-en_US-ljspeech-medium/tokens.txt
    vits-piper-en_US-ljspeech-medium/espeak-ng-data/**

Run the same script once on your machine if you want to build locally.
