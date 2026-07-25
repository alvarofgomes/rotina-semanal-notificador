package com.alvarofgomes.notificador.domain;

import java.time.DayOfWeek;
import java.util.List;
import java.util.Map;

public class DayTrackMap {
    public static final Map<DayOfWeek, List<String>> MAP = Map.of(
        DayOfWeek.MONDAY,    List.of("seg-java", "seg-prat"),
        DayOfWeek.TUESDAY,   List.of("ter-js",   "ter-prat"),
        DayOfWeek.WEDNESDAY, List.of("qua-proj"),
        DayOfWeek.THURSDAY,  List.of("qui-java", "qui-prat"),
        DayOfWeek.FRIDAY,    List.of("sex-mysql")
    );
}
