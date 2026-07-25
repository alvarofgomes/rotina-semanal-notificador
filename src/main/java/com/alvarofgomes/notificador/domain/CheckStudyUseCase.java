package com.alvarofgomes.notificador.domain;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

public class CheckStudyUseCase {

    private final StudyPort studyPort;
    private final NotificationPort notificationPort;

    public CheckStudyUseCase(StudyPort studyPort, NotificationPort notificationPort) {
        this.studyPort = studyPort;
        this.notificationPort = notificationPort;
    }

    public void execute(LocalDate hoje) {
        DayOfWeek dia = hoje.getDayOfWeek();
        if (dia == DayOfWeek.SATURDAY || dia == DayOfWeek.SUNDAY) return;

        String docKey = calcWeekDocKey(hoje);
        if (!studyPort.estudouHoje(docKey, dia)) {
            notificationPort.enviarLembrete();
        }
    }

    public static String calcWeekDocKey(LocalDate date) {
        LocalDate jan1 = LocalDate.of(date.getYear(), 1, 1);
        long daysSince = ChronoUnit.DAYS.between(jan1, date);
        int jan1js = jan1.getDayOfWeek().getValue() % 7;
        int week   = (int) Math.ceil((daysSince + jan1js + 1.0) / 7);
        return date.getYear() + "-W" + week;
    }
}
