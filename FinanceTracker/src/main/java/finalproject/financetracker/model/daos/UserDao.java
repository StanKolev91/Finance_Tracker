package finalproject.financetracker.model.daos;

import finalproject.financetracker.model.pojos.User;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class UserDao {

    static long DEFAULT_USER_ID = 1;

    @Autowired
    UserRepository userRepository;
    @Autowired
    DeletetUserRepository deletetUserRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    public void registerUser(User user) {
        userRepository.save(user);
    }

    public void deleteUser(User user) {
        deletetUserRepository.save(user);
        userRepository.delete(user);
    }

    public void insertDefaultUserIfNotExists() {
        User defaultUser = new User("Default", "Default",
                "Default", "Default", "def@au.lt");
        if (this.getUserByUsername(defaultUser.getUsername()) != null) return;
        userRepository.save(defaultUser);
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
