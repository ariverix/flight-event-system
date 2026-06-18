package ru.protectinfotrans.eca.user.port.out;

/**
 * Узкий выходной порт модуля User для поиска идентификатора пользователя по логину.
 * Используется другими модулями (например, Sequence Manager) для резолва userId
 * по имени аутентифицированного пользователя, без прямой зависимости от
 * внутренних типов модуля User (UserService, User).
 */
public interface UserLookupPort {

    /**
     * Найти id пользователя по username.
     *
     * @param username логин
     * @return id пользователя или null, если пользователь не найден
     */
    Long findUserIdByUsername(String username);
}
