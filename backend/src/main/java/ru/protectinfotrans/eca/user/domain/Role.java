package ru.protectinfotrans.eca.user.domain;

public enum Role {
    OPERATOR,  // мониторинг и просмотр; создание/редактирование последовательностей
    ADMIN      // всё что может OPERATOR + управление пользователями
}
