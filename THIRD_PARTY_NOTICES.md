# Third-party notices

Astiko is licensed under the MIT License (see `LICENSE`). Third-party
components bundled in the app keep their own licenses. Full texts ship
in `app/src/main/assets/licenses/` and are shown in-app via
Settings > Open source licenses.

| Component | License | Full text |
|---|---|---|
| Astiko (this app) | MIT | `LICENSE`, `app/src/main/assets/licenses/mit.txt` |
| Jetpack Compose, Material3, AndroidX (androidx.*) | Apache-2.0 | `app/src/main/assets/licenses/apache-2.0.txt` |
| Retrofit, OkHttp | Apache-2.0 | `app/src/main/assets/licenses/apache-2.0.txt` |
| kotlinx.serialization, kotlinx.coroutines, Kotlin stdlib | Apache-2.0 | `app/src/main/assets/licenses/apache-2.0.txt` |
| MapLibre Android (`org.maplibre.gl:android-sdk-opengl`) | BSD-2-Clause | `app/src/main/assets/licenses/maplibre-bsd-2.txt` |
| Manrope font | SIL OFL 1.1 | `app/src/main/assets/licenses/OFL-Manrope.txt` |
| Map data, OpenStreetMap | ODbL (attribution) | `app/src/main/assets/licenses/osm-attribution.txt` |
| Map styles, OpenFreeMap | ToS (attribution) | `app/src/main/assets/licenses/openfreemap.txt` |
| ΟΣΕΘ GTFS feed via data.gov.gr (Thessaloniki stop catalog) | CC BY 4.0 (attribution) | `app/src/main/assets/licenses/cc-by-4.0.txt` |

Notes:

- MapLibre's native library bundles further components (kdbush.hpp,
  supercluster.hpp, Boost, and others). Their complete notice list
  ships inside the MapLibre SDK AAR and is published at
  <https://github.com/maplibre/maplibre-native/blob/main/platform/android/LICENSE.md>.
- The map attribution control (bottom-left of every map) credits
  MapLibre and OpenStreetMap contributors, as the ODbL requires.
- Transit data comes from the operators' public telematics systems;
  see "Data sources" and the unofficial-app disclaimer in-app. The
  Thessaloniki stop catalog is derived from the ΟΣΕΘ GTFS feed on
  data.gov.gr, licensed CC BY 4.0 (attribution above).
