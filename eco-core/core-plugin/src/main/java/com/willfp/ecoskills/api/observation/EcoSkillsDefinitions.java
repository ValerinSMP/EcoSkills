package com.willfp.ecoskills.api.observation;
import java.util.*;
public record EcoSkillsDefinitions(Map<String,Object> data) {
 public EcoSkillsDefinitions { data=ObservationValues.immutable(data); }
 public Map<String,Object> asMap() { return data; }
}
