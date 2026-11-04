<p align="center"><img src="assets/logo.svg" width="110" alt="Astiko logo"></p>

<h1 align="center">Astiko</h1>

<p align="center"><b>Live arrivals, full-day timetables and live bus maps for 26 Greek cities.</b></p>

<p align="center">
<a href="https://github.com/uw0s/Astiko/releases"><img src="https://img.shields.io/github/v/release/uw0s/Astiko?color=0E6FD8" alt="Latest release" /></a>
<a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-0E6FD8.svg" alt="License: MIT" /></a>
<a href="https://github.com/uw0s/astiko/actions"><img src="https://img.shields.io/github/actions/workflow/status/uw0s/astiko/android-ci.yml?branch=main&label=CI" alt="CI" /></a>
</p>

## Screenshots

<p align="center">
<a href="assets/screenshots/dark/arrivals.webp"><img src="assets/screenshots/dark/arrivals.webp" width="140"></a>
<a href="assets/screenshots/dark/lines.webp"><img src="assets/screenshots/dark/lines.webp" width="140"></a>
<a href="assets/screenshots/dark/line-detail.webp"><img src="assets/screenshots/dark/line-detail.webp" width="140"></a>
<a href="assets/screenshots/dark/nearby-stops.webp"><img src="assets/screenshots/dark/nearby-stops.webp" width="140"></a>
<a href="assets/screenshots/dark/timetable.webp"><img src="assets/screenshots/dark/timetable.webp" width="140"></a>
</p>

## Features

- Nearby stops from GPS, with favorites
- Arrival countdowns with live bus positions on the map
- Line browser: search, direction picker, stops in order, arrivals per stop
- Day-of-week timetables with the next departure highlighted
- Live line map: the route, its stops and buses in transit (MapLibre + OpenFreeMap)
- Offline cache with per-city prefetch and timetable fallback for arrivals

## Data sources

| Source | Cities | Data |
|---|---|---|
| `telematics.oasa.gr` | Athens | stops, arrivals, live vehicles, route geometry |
| `oseth.com.gr` | Thessaloniki | routes, timetables, arrivals, vehicles, geometry |
| `rest.citybus.gr` | 24 cities | stops, arrivals, lines, timetables, geometry |
| `data.gov.gr` | Thessaloniki | stop catalog (GTFS) |

The telematics APIs are unofficial.

## Contributing

Bug reports and pull requests are welcome.

## License

Released under the MIT License. See [LICENSE](LICENSE).
