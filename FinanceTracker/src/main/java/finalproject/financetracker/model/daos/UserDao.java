package finalproject.financetracker.model.daos;

import finalproject.financetracker.exceptions.MyException;
import finalproject.financetracker.exceptions.ServerErrorException;
import finalproject.financetracker.model.pojos.User;
import finalproject.financetracker.model.pojos.VerificationToken;
import finalproject.financetracker.model.repositories.TokenRepository;
import finalproject.financetracker.model.repositories.UserRepository;
import finalproject.financetracker.utils.TimeUtil;
import finalproject.financetracker.utils.emailing.OnRegistrationCompleteEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.WebRequest;

import java.util.*;

@Component
public class UserDao {

    public static Long DEFAULT_CATEGORY_USER_ID = null;
    public static String DEFAULT_USER_USERNAME = "Default";

    @Autowired
    private UserRepository userRepository;
    @Autowired
    private TokenRepository tokenRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private TimeUtil timeUtil;

    @Transactional(rollbackFor = ServerErrorException.class)
    public void verifyUserEmail(User user, VerificationToken token) {
        userRepository.save(user);
        tokenRepository.delete(token);
    }

    @Transactional(rollbackFor = MyException.class)
    public void deleteUser(User user) {
        User deletedUser = userRepository.findByUsername(user.getUsername());
        // TODO consider is_deleted column and validations
        String deletedUserValues = "deleted" + (new Random().nextInt(90000) + 10000) + deletedUser.getUserId();
        // TODO implement after password hashing is possible
        String deletedUserPassword = "deleted" + (new Random().nextInt(90000) + 10000) + deletedUser.getUserId();
        deletedUser.setUsername(deletedUserValues);
        deletedUser.setFirstName(deletedUserValues);
        deletedUser.setLastName(deletedUserValues);
        deletedUser.setEmail(deletedUserValues);
        deletedUser.setEmailConfirmed(false);
        deletedUser.setSubscribed(false);
        VerificationToken vt = tokenRepository.findByUserId(deletedUser.getUserId());
        if (vt != null) {
            tokenRepository.delete(vt);
        }
        userRepository.save(deletedUser);
    }

    /* ----- UPDATE QUERIES ----- */
    public void updateUser(User user) {
        userRepository.save(user);
    }

    public void updateEmail(User user, String newEmail) {
        String sql = "UPDATE final_project.users SET email = ? WHERE user_id = ?;";
        long id = getUserId(user);
        jdbcTemplate.update(sql, newEmail, id);
    }

    /* ----- SELECT QUERIES ----- */

    public User getUserByUsername(String username) {
        return userRepository.findByUsername(username);
    }


    public User getUserByEmail(String email) {
        User user = getUserByStringParam("email", email);
        return user;
    }

    public List<Map<String, Object>> getEmailsToBeNotifiedByReminder() {
        Date notificationReferenceDate = timeUtil.getDateByMonthChange(-1);
        Date loginReferenceDate = timeUtil.getDateByMonthChange(-2);
        List<Map<String, Object>> emails =  jdbcTemplate.queryForList(
                        "SELECT u.email AS email FROM final_project.users AS u " +
                                "JOIN final_project.transactions AS t " +
                                "ON (u.user_id = t.user_id) " +
                                "WHERE u.last_notified < ? AND u.is_subscribed = 1 " +
                                "AND u.is_email_confirmed = 1 AND NOT u.last_login < ? " +
                                "GROUP BY u.user_id " +
                                "HAVING MAX(t.execution_date) < ?;",
                      notificationReferenceDate, loginReferenceDate, notificationReferenceDate
                );
        return emails;
    }

    public void updateUsersLastNotified(List<Map<String, Object>> emails) {
        for (Map<String, Object> email : emails) {
            jdbcTemplate.update("UPDATE final_project.users  SET last_notified = ? WHERE email = ?",
                    new Date(), email.get("email"));
        }
    }

    private User getUserByStringParam(String col, String param) {
        String sql = "SELECT * FROM final_project.users WHERE "+col+" LIKE ?;";
        User user;
            try {
                user = jdbcTemplate.queryForObject(
                        sql, new Object[]{ param }, new BeanPropertyRowMapper<>(User.class));
            } catch (IncorrectResultSizeDataAccessException ex) {
                return null;
            }
        return user;
    }

    public long getUserId(User user) {
        return getUserByUsername(user.getUsername()).getUserId();
    }

}
