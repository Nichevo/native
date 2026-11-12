# Native Finds

An Android app for identifying the birds, trees and flowers you come across, finding out whether
they're native, and keeping a record of each find with its photo, GPS location and notes.

## What it does

- **Photograph** a bird, leaf, flower, fruit, bark or whole plant (or pick a photo from the gallery).
- **Identify** it:
  - Plants: [Pl@ntNet](https://my.plantnet.org) online (free key, 500 IDs a day), or an on-phone
    plant model (~2,100 species) when offline.
  - Birds: an on-phone bird model (~960 species), works offline.
  - **Ask my PC**: an optional second opinion from a vision model running in Ollama on your own
    computer. See [docs/pc-setup.md](docs/pc-setup.md).
  - Or type the name yourself.
- **Species info** from iNaturalist and Wikipedia: photo, description, conservation status, and
  whether it's **endemic, native or introduced** in your region.
- **Region packs**: download the list of birds and plants recorded in a country (New Zealand by
  default; any iNaturalist place works). Matches recorded in the region are ranked first, and
  names and status work offline.
- **Offline mode**: on-phone models and cached data only, with no internet calls. Handy where Google
  and Wikipedia are blocked, or there's no signal.
- **Finds**: list with search, a map (OpenStreetMap), notes, sharing, and CSV export.
- **Seen near you**: birds and plants others have recorded on iNaturalist within 1 to 25 km, ticked when
  you've found them, and a map layer of the last 30 days' sightings.
- **Pest alerts** (New Zealand): pest birds and weeds are flagged, with links to report unwanted organisms
  to MPI or check regional council advice. The list is in `app/src/main/assets/pests/nz.csv`.
- **Stats**: life list with first-found dates, species per year (and new each year), and hotspots on the map.
- **Bird song**: record a calling bird (up to 30 s) and BirdNET identifies it on the phone, offline too. Results are
  filtered to birds recorded in your region, and the recording is saved with the find.

Everything is stored on the phone. Photos are only sent to Pl@ntNet or your own PC when you ask for an
identification; your location is never sent to an identification service.

## Install

1. On your phone, open the [latest release](../../releases/latest) and download `native-finds.apk`.
2. Open it and allow your browser to install apps when Android asks.
3. In the app's **Settings**: add your Pl@ntNet key, check the region, and tap **Download pack**.

New builds are published automatically on every change to `main`, and install over the old version
without losing your finds.

## Build it yourself

Requires JDK 17 and the Android SDK.

```
./scripts/fetch-models.sh     # optional: on-device bird and plant models
./gradlew assembleRelease
```

## Credits

- On-device models: Google AIY vision classifiers (birds V1, plants V1), via Kaggle Models.
- Bird-song identification: BirdNET V2.4 (K. Lisa Yang Center for Conservation Bioacoustics, Cornell Lab of
  Ornithology, and Chemnitz University of Technology), CC BY-NC-SA 4.0, downloaded from Zenodo at build time.
- Plant identification: Pl@ntNet API.
- Species data: iNaturalist API and Wikipedia.
- Maps: OpenStreetMap contributors, via osmdroid.
