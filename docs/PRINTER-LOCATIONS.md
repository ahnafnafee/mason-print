# GMU printer locations

The app resolves GMU printer labels to physical building names and street addresses. The lookup is
restricted to `mobileprint.gmu.edu`; other Pharos servers retain their own labels. The server's
structured building, floor, area and region fields were empty when checked on 19 September 2026.

The [research crosswalk](gmu-printer-buildings.csv) records the sources and confidence for all 80
location groups across 302 printer records and 92 campus-qualified label groups. Fuse (`AR-FSB`)
and Activities Building (`FX-ACT`) were confirmed by the user on that date. There are 67 confirmed
groups, 10 inferred groups and 3 unresolved groups. The latter two categories are visibly marked in
the app; an inferred address is a candidate, and unresolved entries have no invented street address.

Primary sources are GMU's [physical building address directory](https://ready.gmu.edu/fire-safety/addresses-assembly-areas/),
[printer locations](https://printandmail.gmu.edu/locateprinters/),
[replacement schedule](https://abs.gmu.edu/campus-printer-fleet-upgrade-underway/) and
[registrar building codes](https://registrar.gmu.edu/topics/building-codes/).
Renamed buildings are supported by the [Buchanan Hall announcement](https://chss.gmu.edu/articles/10870)
and [Katherine G. Johnson Hall board resolution](https://bov.gmu.edu/wp-content/uploads/meeting-book-full-board-meeting-may-2-2019-updated-5.16.2019.pdf).
These are physical destinations, not the university's general mailing address. Listing a printer
does not establish unrestricted building access or current availability.

## Parsing and presentation

`GmuBuildings.kt` holds the catalog. `PrinterStations.kt` matches exact campus-qualified aliases
before separating a one-digit floor suffix. This preserves numeric codes such as `4260CBR`,
`SUB1` and `NEM2`. `AR-FH` resolves to Van Metre Hall, while `FX-FH` resolves to Field House.
Aliases for ground, lower and basement levels share one building group but retain their floor.
An explicit numeric floor takes precedence when a label includes both, such as `AR-HHB1`.

The building filter and grouped printer list show names, campuses and addresses. Starred and recent
printers, along with the release confirmation, carry their own location details. Original labels
remain visible for matching the physical printer. Search accepts addresses, campuses, old building
names, label aliases, rooms and models, including several terms entered together.

## Updating the mapping

Update the catalog and research crosswalk together, keeping source URLs and unresolved questions.
The public-label-only fixture `app/src/test/resources/gmu-printer-stations.tsv` pins every observed
station to its researched group. Parser tests cover all 302 records, alias collisions, scope, floors,
uncertain addresses and search. New or malformed labels remain selectable even without a catalog
entry. Recheck the official address directory before promoting an inferred location to confirmed.

## Verification

On 19 September 2026, `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease`
completed successfully: 264 tests, no failures, and no lint errors (39 warnings and 4 hints).
The catalog regression test resolves all 302 observed labels to their researched groups.

An offline preview on a 360 dp Android emulator checked the building filter, address search,
grouped list, starred cards, release confirmation and unresolved-address display. Light and dark
themes were inspected, including 1.3 font scaling for a long building name. The temporary preview
activity and sample assets were removed before the final builds; no authenticated print or release
was performed for this location-only change.
