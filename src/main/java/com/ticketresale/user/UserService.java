package com.ticketresale.user;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ticketresale.common.exception.ConflictException;
import com.ticketresale.common.exception.NotFoundException;
import com.ticketresale.db.DbRouting;
import com.ticketresale.user.UserDtos.CreateUserRequest;

@Service
public class UserService {

    private final UserRepository users;

    public UserService(UserRepository users) {
        this.users = users;
    }

    @Transactional
    public AppUser create(CreateUserRequest request) {
        if (users.existsByEmailIgnoreCase(request.email())) {
            throw new ConflictException("Користувач з email " + request.email() + " вже існує");
        }
        return users.save(new AppUser(request.email().toLowerCase(), request.fullName().trim()));
    }

    /**
     * ЛР6: профіль користувача — дані автентифікації (Strong Consistency), тому читання з primary:
     * щойно зареєстрований користувач має одразу проходити перевірки бронювання й купівлі.
     */
    @Transactional(readOnly = true)
    public AppUser get(Long id) {
        return DbRouting.strict(() -> users.findById(id).orElseThrow(() -> new NotFoundException("Користувача", id)));
    }
}
