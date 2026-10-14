package com.willfp.ecoskills.api.observation;
import java.util.*;
public record ObservationCapabilities(Map<String,Object> data) {
 public ObservationCapabilities { data=ObservationValues.immutable(data); }
 public Map<String,Object> asMap() { return data; }
}
