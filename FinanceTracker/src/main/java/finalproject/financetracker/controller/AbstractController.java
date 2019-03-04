package finalproject.financetracker.controller;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.io.JsonEOFException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import finalproject.financetracker.model.exceptions.*;
import finalproject.financetracker.model.exceptions.category_exceptions.CategoryException;
import finalproject.financetracker.model.exceptions.image_exceptions.ImageNotFoundException;
import finalproject.financetracker.model.pojos.ErrMsg;
import finalproject.financetracker.model.pojos.User;
import finalproject.financetracker.model.exceptions.user_exceptions.*;
import javassist.tools.web.BadHttpRequest;
import lombok.NoArgsConstructor;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;
import org.springframework.dao.DataAccessException;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpClientErrorException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Date;
import java.util.Optional;

@NoArgsConstructor
@RestController
public abstract class AbstractController {

    //---------------------< Methods >----------------------//

    private Logger logger = LogManager.getLogger(Logger.class);

    protected void logInfo(String msg) {
        logger.info(msg);
    }

    protected void logInfo(HttpStatus httpStatusCode, Exception e) {
        logger.info(httpStatusCode
                + "\n\tOccurred in class = " + this.getClass()
                + ",\n\tException class = " + e.getClass()
                + "\n\tmsg = " + e.getMessage());
    }

    protected void logWarn(HttpStatus httpStatusCode, Exception e) {
        logger.warn(httpStatusCode
                + "\n\tOccurred in class = " + this.getClass()
                + ",\n\tException class = " + e.getClass()
                + "\n\tmsg = " + e.getMessage());
    }

    protected void logError(HttpStatus httpStatusCode, Exception e) {
        logger.error(httpStatusCode
                + "\n\tOccurred in class = " + this.getClass()
                + ",\n\tException class = " + e.getClass()
                + "\n\tmsg = " + e.getMessage(),e) ;
        logger.error(httpStatusCode + "\n\tOccurred in class = " + this.getClass() + ",\n\tException class = " + e.getClass() + "\n\tmsg = " + e.getMessage(),e);
    }

    protected void validateLogin(HttpSession session) throws NotLoggedInException {
        if (!UserController.isLoggedIn(session)) {
            throw new NotLoggedInException();
        }
    }

    protected User getLoggedValidUserFromSession(HttpSession sess)
            throws
            NotLoggedInException,
            IOException{

        ObjectMapper mapper = new ObjectMapper();
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

        if(!UserController.isLoggedIn(sess)){
            throw new NotLoggedInException();
        }
        return mapper.readValue(sess.getAttribute("User").toString(), User.class);
    }

    protected void checkIfBelongsToLoggedUser(long resourceUserId, User u)
            throws
            NotLoggedInException{

        if (resourceUserId != u.getUserId() ) {
            //TODO chng msg
            throw new NotLoggedInException("not logged in a.userId!=u.userId");
        }
    }

    protected User checkIfBelongsToLoggedUserAndReturnUser(long resourceUserId, HttpSession session)
            throws
            NotLoggedInException,
            IOException {

        User u = getLoggedValidUserFromSession(session);
        if (resourceUserId != u.getUserId() ) {
            //TODO chng msg
            throw new NotLoggedInException("not logged in a.userId!=u.userId");
        }
        return u;
    }

    protected <T extends Object> void checkIfNotNull(Class<?> c,T t ) throws NotFoundException {
        String className = c.getName().substring(c.getName().lastIndexOf(".")+1);
        if (t == null) throw new NotFoundException(className + " not found");
    }

    protected <T> T checkIfNotNull(Class<?> c, Optional<T> o ) throws NotFoundException {
        String className = c.getName().substring(c.getName().lastIndexOf(".")+1);
        if (!o.isPresent())throw new NotFoundException(className + " not found");
        return o.get();
    }

    protected <T> T validateDataAndGetByIdFromRepo(String id,
                                                   JpaRepository<T,Long> repo,
                                                   Class<?>c)
            throws NotFoundException,
            InvalidRequestDataException {

        long idL = checkValidStringId(id);
        Optional<T> t = repo.findById(idL);
        return checkIfNotNull(c,t);
    }

    protected <T> T validateDataAndGetByIdFromRepo(long id,
                                                   JpaRepository<T,Long> repo,
                                                   Class<?>c)
            throws NotFoundException {

        Optional<T> t = repo.findById(id);
        return checkIfNotNull(c,t);
    }

    protected long checkValidStringId(String urlPathId) throws InvalidRequestDataException {
        try {
          return Long.parseLong(urlPathId);
        }catch (Exception e){
            throw new InvalidRequestDataException("invalid id provided");
        }
    }

    public static <T extends Object> String toJson(T u) throws JsonProcessingException {
        ObjectMapper mapper = new ObjectMapper();
        return mapper.writeValueAsString(u);
    }

    public static Integer parseNumber(String num) throws InvalidRequestDataException {
        try {
            return Integer.parseInt(num);
        } catch (IllegalArgumentException ex) {
            throw new InvalidRequestDataException("Non-numeric value given.");
        }
    }


    //---------------------< /Methods >----------------------//

    //---------------------Global Exception Handlers---------------------//
    //todo change msgs ---------------------------------/

    @ExceptionHandler({
            MyException.class,
            JsonProcessingException.class,
            JsonParseException.class,
            JsonEOFException.class,
            HttpClientErrorException.BadRequest.class,
            BadHttpRequest.class,
            ServletException.class,
            HttpMessageNotReadableException.class,
            RegistrationValidationException.class,
            InvalidLoginInfoException.class,
            PasswordValidationException.class,
            CategoryException.class,
            ImageNotFoundException.class})  //400
    public ErrMsg MyExceptionHandler(Exception e, HttpServletResponse resp){
        resp.setStatus(HttpStatus.BAD_REQUEST.value());
        return new ErrMsg(HttpStatus.BAD_REQUEST.value(), e.getMessage(),new Date());
    }

    @ExceptionHandler({
            NotLoggedInException.class,
            HttpClientErrorException.Unauthorized.class, })  // 401
    public ErrMsg MyLoginExceptionHandler(Exception e, HttpServletResponse resp){
        resp.setStatus(HttpStatus.UNAUTHORIZED.value());
        return new ErrMsg(HttpStatus.UNAUTHORIZED.value(), e.getMessage(),new Date());
    }

    @ExceptionHandler({
            ForbiddenRequestException.class})  //403
    public ErrMsg MyForbiddenExceptionHandler(Exception e, HttpServletResponse resp){
        resp.setStatus(HttpStatus.FORBIDDEN.value());
        return new ErrMsg(HttpStatus.FORBIDDEN.value(), e.getMessage(),new Date());
    }

    @ExceptionHandler({NotFoundException.class})  //404
    public ErrMsg MyNotFoundExceptionHandler(Exception e, HttpServletResponse resp){
        resp.setStatus(HttpStatus.NOT_FOUND.value());
        return new ErrMsg(HttpStatus.NOT_FOUND.value(), e.getMessage(),new Date());
    }

    @ExceptionHandler(IOException.class)  //500
    public ErrMsg IOExceptionHandler(Exception e, HttpServletResponse resp) {
        resp.setStatus(HttpStatus.INTERNAL_SERVER_ERROR.value());
        logError(HttpStatus.INTERNAL_SERVER_ERROR,e);
        return new ErrMsg(HttpStatus.INTERNAL_SERVER_ERROR.value(), e.getMessage(),new Date());
    }

    @ExceptionHandler({SQLException.class, DataAccessException.class}) //500
    public ErrMsg SQLExceptionHandler(Exception e, HttpServletResponse resp) {
        resp.setStatus(HttpStatus.INTERNAL_SERVER_ERROR.value());
        logError(HttpStatus.INTERNAL_SERVER_ERROR,e);
        return new ErrMsg(HttpStatus.INTERNAL_SERVER_ERROR.value(), e.getMessage(),new Date());
    }

    @ExceptionHandler(Exception.class) //500
    public ErrMsg ExceptionHandler(Exception e, HttpServletResponse resp){
        resp.setStatus(HttpStatus.INTERNAL_SERVER_ERROR.value());
        logError(HttpStatus.INTERNAL_SERVER_ERROR,e);
        return new ErrMsg(HttpStatus.INTERNAL_SERVER_ERROR.value(), e.getMessage(),new Date());
    }
}
