"""Pure aggregation rules shared by the night analysis, mirroring the app.

Stdlib-only and free of session identity: testable under tools/ pytest (pyproject
testpaths) without matplotlib, importable by analysis/night_report.py via an explicit
sys.path insert (the report runs with `python -I`, which does not add the script dir).
"""

# Mirror of the app's SnoreMath.eventsCountingAsSnore (domain/scoring/SnoreMath.kt,
# snore-score-v3): a non-SNORE user label says the detector misclassified a real sound
# and excludes the event from every snore aggregate; an explicit SNORE label includes
# it; no label keeps the detector's class. Labels: SNORE/COUGH/SPEECH/MOVEMENT/
# OTHER_NOT_SNORE (EventUserLabel.kt). Exported CSVs carry user_label as "" (empty
# cell), not None.
LABEL_COUNTS_AS_SNORE = "SNORE"


def events_counting_as_snore(e: dict) -> bool:
    """Whether one exported event dict counts toward snoring time/share/score."""
    label = e.get("user_label") or ""
    if label:
        return label == LABEL_COUNTS_AS_SNORE
    return e.get("type") == "SNORE"


def match_clip_entry(entries: list[dict], clip_path: str) -> dict | None:
    """The export manifest entry for [clip_path] among successfully pulled clips.

    Entries come from export_night_csv's manifest_<sid>.json; a failed pull
    (exported false) must never be matched — there is no WAV behind it.
    """
    return next(
        (x for x in entries if x.get("path") == clip_path and x.get("exported")),
        None,
    )
