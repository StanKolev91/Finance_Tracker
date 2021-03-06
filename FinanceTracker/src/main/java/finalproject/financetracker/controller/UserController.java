package finalproject.financetracker.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import finalproject.financetracker.exceptions.*;
import finalproject.financetracker.model.daos.TokenDao;
import finalproject.financetracker.model.dtos.MsgObjectDTO;
import finalproject.financetracker.model.repositories.TokenRepository;
import finalproject.financetracker.model.repositories.UserRepository;
import finalproject.financetracker.model.dtos.CommonMsgDTO;
import finalproject.financetracker.model.pojos.User;
import finalproject.financetracker.model.daos.UserDao;
import finalproject.financetracker.model.dtos.userDTOs.*;
import finalproject.financetracker.model.pojos.VerificationToken;
import finalproject.financetracker.utils.emailing.EmailSender;
import finalproject.financetracker.utils.passCrypt.PassCrypter;
import lombok.AllArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import javax.mail.internet.AddressException;
import javax.mail.internet.InternetAddress;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.Map;

@RestController
@RequestMapping(produces = "application/json")
public class UserController extends AbstractController {

    @Autowired
    private UserDao userDao;
    @Autowired
    private TokenRepository tokenRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PassCrypter passCrypter;
    @Autowired
    private EmailSender emailSender;
    @Autowired
    private TokenDao tokenDao;

    /* ----- STATUS CHANGES ----- */

    @PostMapping(value = "/register")
    public MsgObjectDTO registerUser(@RequestBody RegistrationDTO regInfo,
                                     HttpServletRequest request, HttpSession session)
            throws InvalidRequestDataException, FailedActionException, JsonProcessingException {
        regInfo.checkValid();
        String username = regInfo.getUsername().trim();
        String password = regInfo.getPassword().trim();
        String password2 = regInfo.getPassword2().trim();
        String firstName = this.formatName(regInfo.getFirstName());
        String lastName = this.formatName(regInfo.getLastName());
        String email = regInfo.getEmail().trim();
        boolean isSubscribed = regInfo.isSubscribed();
        this.validateUsername(username);
        this.validateEmail(email);
        this.validatePasswordsAtRegistration(password, password2);
        User user = new User(
                username,
                passCrypter.crypt(password),
                firstName,
                lastName,
                email,
                false,
                isSubscribed);
        user.setLastNotified(new Date());
        try {
            userRepository.save(user);
            this.sendVerificationTokenToUser(user);
        } catch (Exception ex) {
            user = userRepository.findByUsername(user.getUsername());
            if (user != null) {
                userDao.deleteUser(user);
            }
            throw new FailedActionException("User registration failed.");
        }
        this.setupSession(session, user, request);
        ProfileInfoDTO profile = this.getProfileInfoDTO(user);
        return new MsgObjectDTO("User registered. Verification email successfully sent.",
                LocalDateTime.now(), profile);
    }
    @PostMapping(value = "/login")
    public MsgObjectDTO loginUser(@RequestBody LoginInfoDTO loginInfo, HttpSession session, HttpServletRequest request)
            throws MyException, JsonProcessingException {
        loginInfo.checkValid();
        String username = loginInfo.getUsername().trim();
        String password = loginInfo.getPassword().trim();
        User user = userDao.getUserByUsername(username);
        if (!isLoggedIn(session)) {
            validateLoginAttempt(username, password);
            this.setupSession(session, user, request);
            user.setLastLogin(new Date());
            userRepository.save(user);
            ProfileInfoDTO profile = this.getProfileInfoDTO(user);
            return new MsgObjectDTO("Login successful.", LocalDateTime.now(), profile);
        } else {
            this.validateIpAddr(session, request);
            throw new AlreadyLoggedInException();
        }
    }
    @PutMapping(value = "/logout")
    public MsgObjectDTO logoutUser(HttpSession session, HttpServletRequest request)
            throws IOException, MyException {
        User user = this.getLoggedValidUserFromSession(session, request);
        session.invalidate();
        ProfileInfoDTO profile = this.getProfileInfoDTO(user);
        return new MsgObjectDTO("Logout successful.", LocalDateTime.now(), profile);
    }
    @GetMapping(value = "/profile")
    public ProfileInfoDTO getLoggedUserProfile(HttpSession session, HttpServletRequest request)
                                throws IOException, MyException {
        User user = this.getLoggedValidUserFromSession(session, request);
        return getProfileInfoDTO(user);
    }
    @PutMapping(value = "/confirm")
    public MsgObjectDTO confirmEmail(@RequestParam(value = "token", required = false) String token,
                                     HttpSession session,
                                     HttpServletRequest request)
            throws MyException, JsonProcessingException {
        if (token == null) {
            throw new InvalidRequestDataException("No token was input.");
        }
        VerificationToken verToken = tokenRepository.findByToken(token);
        this.validateToken(verToken);
        User user = userRepository.getByUserId(verToken.getUserId());
        user.setEmailConfirmed(true);
        this.setupSession(session, user, request);
        userDao.saveUserAndDeleteToken(user, verToken);
        ProfileInfoDTO profile = getProfileInfoDTO(user);
        return new MsgObjectDTO("Email " + user.getEmail() + " was confirmed.", LocalDateTime.now(), profile);
    }

    @PutMapping(value = "/new_token")
    public CommonMsgDTO sendNewToken(HttpSession session, HttpServletRequest request)
            throws IOException, MyException {
        User user = this.getLoggedValidUserFromSession(session, request);
        if ((System.currentTimeMillis() - user.getLastNotified().getTime()) < TokenDao.NEW_TOKEN_INTERVAL) {
            throw new BadRequestException("Cannot send new token yet. Time between new token sending is " +
                    (TokenDao.NEW_TOKEN_INTERVAL / 60 / 1000) + " minutes.");
        }
        user.setLastNotified(new Date());
        this.setupSession(session, user, request);
        this.sendVerificationTokenToUser(user);
        return new CommonMsgDTO("Confirmation email sent successfully to " + user.getEmail() + ".",
                LocalDateTime.now());
    }

    /* ----- PROFILE ACTIONS ----- */

    @PutMapping(value = "/profile/edit")
    public MsgObjectDTO editProfile(@RequestBody ProfileEditDTO profileEditDTO,
                                    HttpSession session, HttpServletRequest request)
                    throws MyException, IOException {
        String dtoMessage = "Profile edited successfully: ";
        int initialIdx = dtoMessage.lastIndexOf("successfully");
        profileEditDTO.checkValid();
        User user = this.getLoggedValidUserFromSession(session, request);
        String password = profileEditDTO.getPassword();
        String newEmail = profileEditDTO.getNewEmail();
        String newPassword = profileEditDTO.getNewPassword();
        String newPassword2 = profileEditDTO.getNewPassword2();
        String firstName = profileEditDTO.getFirstName();
        String lastName = profileEditDTO.getLastName();
        this.validateUserPasswordInput(password, user.getPassword());
        if (newEmail != null && !newEmail.isEmpty()) {
            dtoMessage = dtoMessage.concat(this.changeEmail(user, newEmail));
        }
        if (profileEditDTO.getNewPassword() != null && !profileEditDTO.getNewPassword().isEmpty()) {
            dtoMessage = dtoMessage.concat(this.changePassword(user, newPassword, newPassword2));
        }
        dtoMessage = dtoMessage.concat(this.changeNames(user, firstName, lastName));
        this.setupSession(session, user, request);
        userDao.updateUser(user);
        ProfileInfoDTO profileInfoDTO = this.getProfileInfoDTO(user);
        if (initialIdx == dtoMessage.lastIndexOf("successfully")) {
            dtoMessage = "No changes were made.";
        }
        return new MsgObjectDTO(dtoMessage, LocalDateTime.now(), profileInfoDTO);
    }
    @PutMapping(value = "/profile/edit/subscribe")
    public MsgObjectDTO subscribeEmail(HttpSession session, HttpServletRequest request)
            throws IOException, MyException {
        User user = this.getLoggedValidUserFromSession(session, request);
        if (user.isSubscribed()) {
            throw new BadRequestException("User is already subscribed.");
        }
        return this.setUserSubscriptionAndGetMessage(user, session, request, true);
    }
    @PutMapping(value = "/profile/edit/unsubscribe")
    public MsgObjectDTO unsubscribeEmail(HttpSession session, HttpServletRequest request)
            throws IOException, MyException {
        User user = this.getLoggedValidUserFromSession(session, request);
        if (!user.isSubscribed()) {
            throw new BadRequestException("User is already unsubscribed.");
        }
        return this.setUserSubscriptionAndGetMessage(user, session, request,false);
    }
    @PostMapping(value = "/reset_password")
    public CommonMsgDTO sendPasswordResetEmail(@RequestBody Map<String, String> email)
            throws MyException {
        User user = userRepository.findByEmail(email.get("email"));
        if (user == null) {
            throw new NotFoundException("Email not found.");
        }
        if (!user.isEmailConfirmed()) {
            throw new UnauthorizedAccessException("Email not confirmed.");
        }
        this.sendPasswordResetTokenToUser(user);
        return new CommonMsgDTO("Password reset key sent to " + user.getEmail() + ".", LocalDateTime.now());
    }
    @PutMapping(value = "/reset_password")
    public CommonMsgDTO activatePasswordReset(@RequestParam(value = "token", required = false) String token,
                                              HttpSession session,
                                              HttpServletRequest request)
            throws MyException, JsonProcessingException {
        if (token == null) {
            throw new InvalidRequestDataException("No token was input.");
        }
        @AllArgsConstructor
        class ResetPassInvalidator extends Thread {
            private User u;
            private HttpSession sess;
            private String ipAddr;

            @Override
            public void run() {
                try {
                    Thread.sleep(VerificationToken.PASSWORD_RESET_EXPIRATION_MIN * 1000 * 60);
                } catch (InterruptedException ex) {
                    System.out.println("Password reset eligibility reverter interrupted.");
                    logError(HttpStatus.INTERNAL_SERVER_ERROR, ex);
                }
                u.setEligibleForPasswordReset(false);
                userRepository.save(u);
                try {
                    sess.setAttribute(SESSION_USER_KEY, AbstractController.toJson(u));
                    sess.setAttribute(SESSION_USERNAME_KEY, u.getUsername());
                    sess.setAttribute(SESSION_IP_ADDR_KEY, ipAddr);
                } catch (JsonProcessingException e) {
                    logError(HttpStatus.INTERNAL_SERVER_ERROR, e);
                    sess.invalidate();
                }
            }
        }
        VerificationToken verToken = tokenRepository.findByToken(token);
        validateToken(verToken);
        User user = userRepository.getByUserId(verToken.getUserId());
        user.setEligibleForPasswordReset(true);
        userDao.saveUserAndDeleteToken(user, verToken);
        this.setupSession(session, user, request);
        new ResetPassInvalidator(user, session, request.getRemoteAddr()).start();
        return new CommonMsgDTO("User set for password reset.", LocalDateTime.now());
    }
    @PutMapping(value = "/profile/edit/reset_password")
    public MsgObjectDTO resetPassword(@RequestBody PasswordResetDTO passwordResetDTO, HttpSession session,
                                      HttpServletRequest request)
            throws IOException, MyException {
        passwordResetDTO.checkValid();
        User user = this.getLoggedValidUserFromSession(session, request);
        if (!user.isEligibleForPasswordReset()) {
            session.invalidate();
            throw new UnauthorizedAccessException("User is not eligible for password reset.");
        }
        String password = passwordResetDTO.getPassword().trim();
        String password2 = passwordResetDTO.getPassword2().trim();
        this.validateNewPassword(password, password2);
        user.setPassword(passCrypter.crypt(password));
        user.setEligibleForPasswordReset(false);
        userRepository.save(user);
        this.setupSession(session, user, request);
        ProfileInfoDTO profileInfoDTO = this.getProfileInfoDTO(user);
        return new MsgObjectDTO("Password changed successfully.", LocalDateTime.now(), profileInfoDTO);
    }
    @DeleteMapping(value = "/profile")
    public MsgObjectDTO deleteProfile(@RequestBody Map<String, String> password, HttpSession session,
                                      HttpServletRequest request)
            throws IOException, MyException {
        User user = this.getLoggedValidUserFromSession(session, request);
        this.validateUserPasswordInput(password.get("password"), (user.getPassword()));
        userDao.deleteUser(user);
        session.invalidate();
        ProfileInfoDTO profile = getProfileInfoDTO(user);
        return new MsgObjectDTO("User deleted successfully.", LocalDateTime.now(), profile);
    }
    /* ----- VALIDATIONS ----- */

    static boolean isLoggedIn(HttpSession session) {
        return !(session.isNew() || session.getAttribute("Username") == null);
    }

    private void validateEmail(String email) throws InvalidRequestDataException {
        try {
            InternetAddress emailAddr = new InternetAddress(email);
            emailAddr.validate();
        } catch (AddressException ex) {
            throw new InvalidRequestDataException("Invalid email given.");
        }
        if (userDao.getUserByEmail(email) != null) {
            throw new InvalidRequestDataException("Email is already taken.");
        }
    }
    private void validatePasswordsAtRegistration(String password, String password2)
            throws InvalidRequestDataException {
        if (password == null || password2 == null) {
            throw new InvalidRequestDataException("Null value for passwords at user registration.");
        }
        if (!password.equals(password2)) {
            throw new InvalidRequestDataException("Passwords don't match.");
        }
        validatePasswordFormat(password);
    }
    private void validateNewPassword(String newPass, String newPass2)
            throws InvalidRequestDataException {
        if (newPass == null || newPass2 == null) {
            throw new InvalidRequestDataException("Null value for passwords at password change.");
        }
        if (!newPass.equals(newPass2)) {
            throw new InvalidRequestDataException("Passwords don't match.");
        }
        validatePasswordFormat(newPass);
    }
    private void validatePasswordFormat(String password) throws InvalidRequestDataException {
        if (!password.matches("^(?=.*?[A-Z])(?=.*?[a-z])(?=.*?[0-9])(?=.*?[#?!@$%^&*-]).{8,}$")) {
            throw new InvalidRequestDataException("Invalid password format: must contain at least 8 characters, " +
                    "at least one upper case and one lower case letter, at least one number and at least one special " +
                    "character");
        }
    }
    private void validateUsername(String username) throws InvalidRequestDataException {
        if (username.isEmpty() || username.equals(UserDao.DEFAULT_USER_USERNAME)) {
            throw new InvalidRequestDataException("Invalid username input.");
        } else if (username.contains(" ")) {
            throw new InvalidRequestDataException("Username must not contain empty spaces.");
        }
        if (userDao.getUserByUsername(username) != null) {
            throw new InvalidRequestDataException("Username already taken.");
        }
    }
    private void validateLoginAttempt(String username, String password) throws InvalidRequestDataException {
        User user = userDao.getUserByUsername(username);
        if (user == null || !passCrypter.check(password, user.getPassword())) {
            throw new InvalidRequestDataException("Wrong user or password.");
        }
    }
    private void validateToken(VerificationToken token) throws MyException {
        if (token == null) {
            throw new NotFoundException("Token was not found.");
        }
        if (token.getExpiryDate().before(new Date())) {
            tokenRepository.delete(token);
            throw new InvalidRequestDataException("That token has already expired.");
        }
    }
    private void validateUserPasswordInput(String givenPass, String userPass) throws InvalidRequestDataException {
        if (!passCrypter.check(givenPass, userPass)) {
            throw new InvalidRequestDataException("Wrong password.");
        }
    }

    /* ----- OTHER METHODS ----- */
    private String changePassword(User user, String newPassword, String newPassword2)
            throws MyException {
        newPassword = newPassword.trim();
        newPassword2 = newPassword2.trim();
        if (passCrypter.check(newPassword, user.getPassword())) {
            return "";
        }
        validateNewPassword(newPassword, newPassword2);
        user.setPassword(passCrypter.crypt(newPassword));
        return "Password changed successfully. ";
    }
    private String changeEmail(User user, String newEmail)
            throws MyException {
        newEmail = newEmail.trim();
        if (newEmail.equals(user.getEmail())) {
            return "";
        }
        this.validateEmail(newEmail);
        user.setEmail(newEmail);
        user.setEmailConfirmed(false);
        this.sendVerificationTokenToUser(user);
        return "Email changed successfully. Confirmation token sent. ";
    }
    private String changeNames(User user, String firstName, String lastName) {
        firstName = this.formatName(firstName);
        lastName = this.formatName(lastName);
        if (((firstName == null && user.getFirstName() == null) ||
            (user.getFirstName() != null && user.getFirstName().equals(firstName)))
                &&
            ((lastName == null && user.getLastName() == null) ||
            (user.getLastName() != null && user.getLastName().equals(lastName)))) {
            return "";
        }
        user.setFirstName(firstName);
        user.setLastName(lastName);
        return "Name edited successfully.";
    }
    private void setupSession(HttpSession session, User user, HttpServletRequest request)
            throws JsonProcessingException {
        session.setAttribute(SESSION_USER_KEY, AbstractController.toJson(user));
        session.setAttribute(SESSION_USERNAME_KEY, user.getUsername());
        session.setAttribute(SESSION_IP_ADDR_KEY, request.getRemoteAddr());
        session.setMaxInactiveInterval(-1);
    }
    private String formatName(String name) {
        if (name != null && name.trim().isEmpty()) {
            return null;
        }
        if (name != null) name = name.trim();
        return name;
    }
    private void sendVerificationTokenToUser(User user)
            throws EmailSender.EmailAlreadyConfirmedException, InvalidRequestDataException {
        VerificationToken token = tokenDao.getNewToken(user, false);
        emailSender.sendEmailConfirmationToken(user, token);
    }
    private void sendPasswordResetTokenToUser(User user)
            throws UnauthorizedAccessException, InvalidRequestDataException {
        VerificationToken token = tokenDao.getNewToken(user, true);
        emailSender.sendPasswordResetLink(user, token);
    }
    private ProfileInfoDTO getProfileInfoDTO(User user) {
        return new ProfileInfoDTO(user.getUserId(), user.getUsername(),
                user.getFirstName(), user.getLastName(), user.getEmail(),
                user.isEmailConfirmed(), user.isSubscribed());
    }
    private MsgObjectDTO setUserSubscriptionAndGetMessage(User user, HttpSession session,
                                                          HttpServletRequest request, boolean isSubscribing)
                            throws JsonProcessingException {
        String text = "subscribed";
        if (!isSubscribing) text = "unsubscribed";
        user.setSubscribed(isSubscribing);
        userRepository.save(user);
        this.setupSession(session, user, request);
        ProfileInfoDTO profileInfoDTO = this.getProfileInfoDTO(user);
        return new MsgObjectDTO(user.getEmail() + " successfully " + text + ".", LocalDateTime.now(), profileInfoDTO);
    }
}
