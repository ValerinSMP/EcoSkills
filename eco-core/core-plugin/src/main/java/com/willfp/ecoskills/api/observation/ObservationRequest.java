package com.willfp.ecoskills.api.observation;
import java.util.*;
public record ObservationRequest(Set<Section> sections) {
 public enum Section { SKILLS, STATS, EFFECTS, PREFERENCES, MAGIC, MODIFIERS, RANKINGS, CONTEXT, PRESENTATION, HISTORICAL }
 public ObservationRequest { Objects.requireNonNull(sections); sections=Collections.unmodifiableSet(sections.isEmpty()?EnumSet.noneOf(Section.class):EnumSet.copyOf(sections)); }
 public static ObservationRequest all() { return new ObservationRequest(EnumSet.allOf(Section.class)); }
 public static ObservationRequest persistentOnly() { return new ObservationRequest(EnumSet.of(Section.SKILLS,Section.STATS,Section.EFFECTS,Section.PREFERENCES)); }
 public boolean includes(Section section) { return sections.contains(section); }
}
