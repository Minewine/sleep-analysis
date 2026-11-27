"""Tests for the analysis-side pure rules (night_aggregates.py).

The module lives in analysis/ and is deliberately stdlib-only so it stays testable
under the tools/ testpaths without matplotlib; point sys.path at it explicitly.
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "analysis"))

from night_aggregates import events_counting_as_snore, match_clip_entry


def event(**overrides):
    base = {"type": "SNORE", "user_label": ""}
    base.update(overrides)
    return base


def test_detector_snore_without_label_counts():
    assert events_counting_as_snore(event(type="SNORE", user_label=""))


def test_explicit_snore_label_counts():
    assert events_counting_as_snore(event(type="OTHER_NOISE", user_label="SNORE"))


def test_non_snore_labels_exclude_detector_snores():
    """Mirror of SnoreMath.eventsCountingAsSnore (snore-score-v3): a user label saying
    "this was not a snore" excludes the event from every snore aggregate."""
    for label in ("COUGH", "SPEECH", "MOVEMENT", "OTHER_NOT_SNORE"):
        assert not events_counting_as_snore(event(type="SNORE", user_label=label))


def test_noise_without_label_never_counts():
    assert not events_counting_as_snore(event(type="OTHER_NOISE", user_label=""))


def test_none_label_behaves_like_empty_cell():
    assert events_counting_as_snore(event(type="SNORE", user_label=None))
    assert not events_counting_as_snore(event(type="OTHER_NOISE", user_label=None))


def entry(**overrides):
    base = {"path": "evt_snore_123_1.wav", "exported": True}
    base.update(overrides)
    return base


def test_match_clip_entry_finds_exported_clip():
    entries = [entry(path="a.wav"), entry(path="evt_snore_123_1.wav")]
    assert match_clip_entry(entries, "evt_snore_123_1.wav") == entries[1]


def test_match_clip_entry_skips_failed_pulls():
    """A not-exported entry has no WAV behind it — it must never match."""
    entries = [entry(path="evt_snore_123_1.wav", exported=False)]
    assert match_clip_entry(entries, "evt_snore_123_1.wav") is None


def test_match_clip_entry_none_when_absent():
    assert match_clip_entry([], "x.wav") is None
    assert match_clip_entry([entry(path="a.wav")], "x.wav") is None
