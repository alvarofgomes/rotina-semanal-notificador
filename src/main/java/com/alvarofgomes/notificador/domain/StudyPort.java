package com.alvarofgomes.notificador.domain;

import java.time.DayOfWeek;

public interface StudyPort {
    boolean estudouHoje(String weekDocKey, DayOfWeek dia);
}
