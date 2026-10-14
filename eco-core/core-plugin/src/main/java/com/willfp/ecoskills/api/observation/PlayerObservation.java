package com.willfp.ecoskills.api.observation;
import java.util.*;
public record PlayerObservation(Map<String,Object> data) {
 public PlayerObservation { data=ObservationValues.immutable(data); }
 public Map<String,Object> asMap() { return data; }
}
