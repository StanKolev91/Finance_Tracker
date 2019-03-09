package finalproject.financetracker.controller;

import finalproject.financetracker.exceptions.ForbiddenRequestException;
import finalproject.financetracker.exceptions.MyException;
import finalproject.financetracker.model.daos.AbstractDao;
import finalproject.financetracker.model.daos.PlannedTransactionDao;
import finalproject.financetracker.model.dtos.account.ReturnAccountDTO;
import finalproject.financetracker.model.dtos.plannedTransaction.AddPlannedTransactionDTO;
import finalproject.financetracker.model.dtos.plannedTransaction.ReturnPlannedTransactionDTO;
import finalproject.financetracker.model.dtos.plannedTransaction.UpdatePlannedTransactionDTO;
import finalproject.financetracker.model.pojos.Category;
import finalproject.financetracker.model.pojos.PlannedTransaction;
import finalproject.financetracker.model.pojos.Transaction;
import finalproject.financetracker.model.pojos.User;
import finalproject.financetracker.model.repositories.PlannedTransactionRepo;
import finalproject.financetracker.model.repositories.TransactionRepo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

@RequestMapping(value = "/profile")
@Controller
public class PlannedTransactionController extends AbstractController {
    static final ReentrantLock concurrentLock = new ReentrantLock();
    @Autowired
    private PlannedTransactionRepo repo;
    @Autowired
    private PlannedTransactionDao dao;
    @Autowired
    private AccountController accountController;
    @Autowired
    private CategoryController categoryController;
    @Autowired
    private TransactionController transactionController;
    @Autowired
    private TransactionRepo transactionRepo;


    //--------------add transaction for given account---------------------//
    @RequestMapping(value = "/ptransactions", method = RequestMethod.POST)
    @ResponseBody
    public ReturnPlannedTransactionDTO addPlannedTransaction(@RequestBody AddPlannedTransactionDTO addTransactionDTO,
                                                             HttpSession sess, HttpServletRequest request)
            throws
            IOException,
            SQLException,
            MyException {

        User u = getLoggedValidUserFromSession(sess, request);
        addTransactionDTO.checkValid();
        List<PlannedTransaction> transactions = repo.findAllByAccountId(addTransactionDTO.getAccountId());
        Category c = categoryController.getCategoryById(addTransactionDTO.getCategoryId(), sess, request);// WebService
        ReturnAccountDTO a = accountController.getAccByIdLong(addTransactionDTO.getAccountId(), sess, request);

        for (PlannedTransaction transaction : transactions) {
            if (addTransactionDTO.getTransactionName().equalsIgnoreCase(transaction.getPtName())) {
                throw new ForbiddenRequestException("planned transaction with such name exists");
            }
        }
        long waitMillisToExec = addTransactionDTO.getExecutionOffset();
        PlannedTransaction t = new PlannedTransaction(
                addTransactionDTO.getTransactionName(),
                addTransactionDTO.getAmount(),
                LocalDateTime
                        .now()
                        .plusSeconds(addTransactionDTO.getExecutionOffset() / SEC_TO_MILLIS),
                addTransactionDTO.getAccountId(),
                addTransactionDTO.getCategoryId(),
                addTransactionDTO.getRepeatPeriod());
        new Thread(() -> {
            try {
                Thread.sleep(waitMillisToExec);
                execute(t,a.getUserId());
            } catch (Exception e) {
                logError(HttpStatus.INTERNAL_SERVER_ERROR, e);
            }
        }).start();
        return new ReturnPlannedTransactionDTO(repo.save(t))
                .withUser(u)
                .withCategory(c)
                .withAccount(a);
    }

    //-------------- get transaction by transactionId ---------------------//
    @RequestMapping(value = "/ptransactions/{id}", method = RequestMethod.GET)
    @ResponseBody
    public ReturnPlannedTransactionDTO getPlannedTransactionById(@PathVariable(value = "id") String id,
                                                                 HttpSession sess, HttpServletRequest request)
            throws
            IOException,
            SQLException,
            MyException {

        User u = getLoggedValidUserFromSession(sess, request);
        PlannedTransaction pt = validateDataAndGetByIdFromRepo(id, repo, PlannedTransaction.class);
        ReturnAccountDTO a = accountController.getAccByIdLong(pt.getAccountId(), sess, request);
        checkIfBelongsToLoggedUser(a.getUserId(), u);
        Category c = categoryController.getCategoryById(pt.getCategoryId(), sess, request);

        return new ReturnPlannedTransactionDTO(pt)
                .withUser(u)
                .withCategory(c)
                .withAccount(a);
    }

    //--------------get all transactions for given user---------------------//
    @RequestMapping(value = "/ptransactions", method = RequestMethod.GET)
    @ResponseBody
    public List<ReturnPlannedTransactionDTO> getAllPlannedTransaction(
            @RequestParam(value = "acc", required = false) String accId,
            @RequestParam(value = "cat", required = false) String catId,
            @RequestParam(value = "order", required = false) String order,
            @RequestParam(value = "desc", required = false) String desc,
            @RequestParam(value = "income", required = false) String income,
            HttpSession sess, HttpServletRequest request)
            throws
            IOException,
            SQLException,
            MyException {

        User u = getLoggedValidUserFromSession(sess, request);
        Long accIdL = null;
        if (accId != null && !accId.isEmpty()) {
            accIdL = parseLong(accId);
            accountController.getAccByIdLong(accIdL, sess, request);
        }
        Category c = null;
        Long catIdL = null;
        if (catId != null && !catId.isEmpty()) {
            catIdL = parseLong(catId);
            c = categoryController.getCategoryById(catIdL, sess, request);
        }
        Boolean isIncome = null;
        if (income != null && !income.isEmpty()) {
            if (income.equalsIgnoreCase("true")) isIncome = true;
            if (income.equalsIgnoreCase("false")) isIncome = false;
            if (c != null) {
                isIncome = c.isIncome();
            }
        }
        AbstractDao.SQLColumnName columnName = AbstractDao.SQLColumnName.NEXT_EXECUTION_DATE;
        if (order != null) {
            switch (order) {
                case "amount": {
                    columnName = AbstractDao.SQLColumnName.AMOUNT;
                    break;
                }
                case "ptname": {
                    columnName = AbstractDao.SQLColumnName.PT_NAME;
                    break;
                }
                case "aname": {
                    columnName = AbstractDao.SQLColumnName.ACCOUNT_NAME;
                    break;
                }
                case "cname": {
                    columnName = AbstractDao.SQLColumnName.CATEGORY_NAME;
                    break;
                }
            }
        }
        AbstractDao.SQLOderBy orderBy = AbstractDao.SQLOderBy.ASC;
        if (desc != null) {
            if (desc.equalsIgnoreCase("true")) orderBy = AbstractDao.SQLOderBy.DESC;
        }
        return dao.getAllByAccIdIsIncomeOrder(
                u.getUserId(),
                accIdL,
                catIdL,
                isIncome,
                columnName,
                orderBy);
    }

    //-------------- edit transaction ---------------------//
    @RequestMapping(value = "/ptransactions", method = RequestMethod.PUT)
    @ResponseBody
    public ReturnPlannedTransactionDTO updatePlannedTransaction(
            @RequestBody UpdatePlannedTransactionDTO transactionDTO,
            HttpSession sess,
            HttpServletRequest request)

            throws
            IOException,
            SQLException,
            MyException {

        transactionDTO.checkValid();
        User u = getLoggedValidUserFromSession(sess, request);
        PlannedTransaction pt = validateDataAndGetByIdFromRepo(
                transactionDTO.getTransactionId(),
                repo,
                PlannedTransaction.class
        );
        pt.setPtName(transactionDTO.getTransactionName());
        if (transactionDTO.getRepeatPeriod() > pt.getRepeatPeriod()) {
            pt.setNextExecutionDate(
                    pt.getNextExecutionDate()
                            .plusSeconds(
                                    (transactionDTO.getRepeatPeriod() - pt.getRepeatPeriod()) / SEC_TO_MILLIS));
        } else {
            pt.setNextExecutionDate(
                    pt.getNextExecutionDate()
                            .minusSeconds((pt.getRepeatPeriod() - transactionDTO.getRepeatPeriod()) / SEC_TO_MILLIS));
        }
        pt.setRepeatPeriod(transactionDTO.getRepeatPeriod());
        ReturnAccountDTO a = accountController.getAccByIdLong(pt.getAccountId(), sess, request);
        checkIfBelongsToLoggedUser(a.getUserId(), u);
        Category c = categoryController.getCategoryById(pt.getCategoryId(), sess, request);

        pt = repo.saveAndFlush(pt);
        return new ReturnPlannedTransactionDTO(pt)
                .withUser(u)
                .withAccount(a)
                .withCategory(c);
    }

    @RequestMapping(value = "/ptransactions/{id}", method = RequestMethod.DELETE)
    @ResponseBody
    @Transactional
    public ReturnPlannedTransactionDTO deletePlannedTransaction(@PathVariable(value = "id") String deleteId,
                                                                HttpSession sess, HttpServletRequest request)
            throws
            IOException,
            SQLException,
            MyException {

        ReturnPlannedTransactionDTO t = getPlannedTransactionById(deleteId, sess, request);
        repo.deleteByPtId(t.getTransactionId());
        return t;
    }

    @Transactional(rollbackFor = Exception.class)
    public void execute(PlannedTransaction pt, long userId) throws SQLException {
        Transaction t = new Transaction(
                pt.getPtName()
                        .concat(LocalDateTime.now()
                                .format(DateTimeFormatter.ofPattern("(dd.MM.YY/HH:mm:ss.SSS)"))),
                pt.getPtAmount(),
                LocalDateTime.now(),
                pt.getAccountId(),
                pt.getCategoryId());
        transactionController.calculateBudgetAndAccountAmount(t, userId);
        this.transactionRepo.save(t);
        reSchedule(pt);
    }

    private void reSchedule(PlannedTransaction pt) {
        pt.setNextExecutionDate(pt.getNextExecutionDate().plusSeconds(pt.getRepeatPeriod() / SEC_TO_MILLIS));
        repo.save(pt);
    }

    void startScheduledCheck() {
        new PlannedTransactionScheduler().start();
    }


    private class PlannedTransactionScheduler extends Thread {

        private final int delay = 30000;

        @Override
        public void run() {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                logError(HttpStatus.INTERNAL_SERVER_ERROR, e);
            } finally {
                logInfo("PlannedTransactionScheduler is performing check...");
                List<ReturnPlannedTransactionDTO> transactions = PlannedTransactionController.this.dao.getAllWhereExecDateBeofreNext24Hours();
                logInfo("Found " + transactions.size() + " overdue planned transactions.");

                for (ReturnPlannedTransactionDTO pt : transactions) {
                    new PlannedTransactionExecutor(pt).start();
                }
            }
        }

        private class PlannedTransactionExecutor extends Thread {
            private ReturnPlannedTransactionDTO plannedTransactionDTO;

            PlannedTransactionExecutor(ReturnPlannedTransactionDTO pt) {
                this.plannedTransactionDTO = pt;
            }

            @Override
            public void run() {
                logInfo("New PlannedTransactionExecutor started for "
                        + this.plannedTransactionDTO);
                PlannedTransaction pt = new PlannedTransaction(plannedTransactionDTO.getTransactionName(),
                        plannedTransactionDTO.getAmount(),
                        plannedTransactionDTO.getNextExecutionDate(),
                        plannedTransactionDTO.getAccountId(),
                        plannedTransactionDTO.getCategoryId(),
                        plannedTransactionDTO.getRepeatPeriod());
                while (this.plannedTransactionDTO.getNextExecutionDate().isBefore(LocalDateTime.now())) {

                    try {
                        synchronized (PlannedTransactionController.concurrentLock) {
                            PlannedTransactionController.this.execute(pt, plannedTransactionDTO.getUserId());
                        }
                    } catch (Exception e) {
                        logError(HttpStatus.INTERNAL_SERVER_ERROR, e);
                    }
                }
                logInfo("Updated..... "+this.plannedTransactionDTO);
                logInfo("Checking for execution in next 24 hours.");
                try {
                    if (this.plannedTransactionDTO.getNextExecutionDate().isBefore(LocalDateTime.now().plusDays(1))) {
                        logInfo("Waiting..... "+ plannedTransactionDTO);
                        Thread.sleep(
                                this.plannedTransactionDTO.getNextExecutionDate()
                                        .toEpochSecond(ZoneOffset.UTC) * SEC_TO_MILLIS
                                        -
                                        LocalDateTime.now()
                                                .toEpochSecond(ZoneOffset.UTC) * SEC_TO_MILLIS);
                        logInfo("Executing... " + this.plannedTransactionDTO);
                        synchronized (PlannedTransactionController.concurrentLock) {
                            PlannedTransactionController.this.execute(pt, plannedTransactionDTO.getUserId());
                        }
                        logInfo("Executed.... "+this.plannedTransactionDTO);
                    }
                } catch (Exception e) {
                    logError(HttpStatus.INTERNAL_SERVER_ERROR, e);
                }
            }
        }
    }
}


