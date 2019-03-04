package finalproject.financetracker.controller;

import finalproject.financetracker.model.daos.BudgetRepository;
import finalproject.financetracker.model.daos.UserRepository;
import finalproject.financetracker.model.dtos.budgetDTOs.BudgetCreationDTO;
import finalproject.financetracker.model.dtos.budgetDTOs.BudgetInfoDTO;
import finalproject.financetracker.model.dtos.budgetDTOs.BudgetsViewDTO;
import finalproject.financetracker.model.exceptions.MyException;
import finalproject.financetracker.model.exceptions.NotLoggedInException;
import finalproject.financetracker.model.exceptions.budget_exceptions.BudgetDatesException;
import finalproject.financetracker.model.exceptions.budget_exceptions.BudgetNotFoundException;
import finalproject.financetracker.model.pojos.Budget;
import finalproject.financetracker.model.pojos.User;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping(value = "/profile", produces = "application/json")
public class BudgetController extends AbstractController {

    @Autowired
    private BudgetRepository budgetRepository;
    @Autowired
    private UserRepository userRepository;


    @GetMapping(value = "/budgets")
    public BudgetsViewDTO viewBudgets(HttpSession session) throws IOException, MyException {
        User user = this.getLoggedValidUserFromSession(session);
        List<Budget> budgets = budgetRepository.findAllByUserId(user.getUserId());
        List<BudgetInfoDTO> budgetsInfo = new ArrayList<>();
        for (Budget budget : budgets) {
            BudgetInfoDTO budgetInfoDTO = new BudgetInfoDTO(
                    budget.getBudgetId(), budget.getBudgetName(),
                    budget.getAmount(), budget.getStartingDate(),
                    budget.getEndDate(), budget.getUserId(), budget.getCategoryId());
            budgetsInfo.add(budgetInfoDTO);
        }
        return new BudgetsViewDTO(budgetsInfo);
    }

    @GetMapping(value = "/budgets/{budgetId}")
    public BudgetInfoDTO viewBudget(@PathVariable long budgetId, HttpSession session) throws IOException, MyException {
        User user = this.getLoggedValidUserFromSession(session);
        Budget budget = budgetRepository.findByBudgetId(budgetId);
        this.validateBudgetOwnership(budget, user.getUserId());
        return this.getBudgetInfoDTO(budget);
    }

    @PostMapping(value = "/budgets")
    public BudgetInfoDTO createBudget(@RequestBody BudgetCreationDTO budgetCreationDTO, HttpSession session)
                                    throws IOException, MyException {
        budgetCreationDTO.checkValid();
        User user = this.getLoggedValidUserFromSession(session);
        String budgetName = budgetCreationDTO.getBudgetName();
        double amount = budgetCreationDTO.getAmount();
        LocalDate startingDate = budgetCreationDTO.getStartingDate();
        LocalDate endDate = budgetCreationDTO.getEndDate();
        long userId = user.getUserId();
        long categoryId = budgetCreationDTO.getCategoryId();
        this.validateDates(startingDate, endDate);
        Budget budget = new Budget(budgetName, amount, startingDate, endDate, userId, categoryId);
        budgetRepository.save(budget);
        budget = budgetRepository.findByBudgetNameAndUserId(budgetName, userId);
        return getBudgetInfoDTO(budget);
    }

    @PutMapping(value = "/budgets/{budgetId}")
    public BudgetInfoDTO editBudget(
            @PathVariable long budgetId,
            @RequestParam(value = "budgetName", required = false) String budgetName,
            @RequestParam(value = "amount", required = false) Double amount,
            @RequestParam(value = "endDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(value = "categoryId", required = false) Long categoryId,
            HttpSession session)
                                throws IOException, MyException {
        User user = this.getLoggedValidUserFromSession(session);
        Budget budget = budgetRepository.findByBudgetId(budgetId);
        this.validateBudgetOwnership(budget, user.getUserId());
        if (budgetName != null) {
            budget.setBudgetName(budgetName);
        }
        if (amount != null) {
            budget.setAmount(amount);
        }
        if (endDate != null) {
            this.validateDates(budget.getStartingDate(), endDate);
            budget.setEndDate(endDate);
        }
        if (categoryId != null) {
            budget.setCategoryId(categoryId);
        }
        budgetRepository.save(budget);
        return getBudgetInfoDTO(budget);
    }

    @DeleteMapping(value = "/budgets/{budgetId}")
    public BudgetInfoDTO deleteBudget(@PathVariable long budgetId, HttpSession session) throws IOException, MyException {
        User user = this.getLoggedValidUserFromSession(session);
        Budget budget = budgetRepository.findByBudgetId(budgetId);
        this.validateBudgetOwnership(budget, user.getUserId());
        budgetRepository.delete(budget);
        return this.getBudgetInfoDTO(budget);
    }

    public void subtractFromBudgets(double amount, long userId, long categoryId) {
        List<Budget> budgets = budgetRepository.findAllByUserId(userId);
        List<Budget> nearingLimit = new ArrayList<>();
        for (Budget budget : budgets) {
            if (budget.getCategoryId() == categoryId && !budget.getEndDate().isAfter(LocalDate.now())) {
                budget.setAmount(budget.getAmount() - amount);
                // TODO send email / message if budget is near 0
                if (budget.getAmount() <= 50) {
                    nearingLimit.add(budget);
                }
                //
                budgetRepository.save(budget);
            }
        }
        if (nearingLimit.size() > 0) {
            this.sendBudgetLimitEmail(userId, nearingLimit);
        }
    }

    private BudgetInfoDTO getBudgetInfoDTO(Budget budget) {
        return new BudgetInfoDTO(
                budget.getBudgetId(), budget.getBudgetName(),
                budget.getAmount(), budget.getStartingDate(),
                budget.getEndDate(), budget.getUserId(), budget.getCategoryId());
    }

    private void sendBudgetLimitEmail(long userId, List<Budget> budgets) {
        User user = userRepository.getByUserId(userId);
        if (user.isEmailConfirmed() && user.isSubscribed()) {
            System.out.println("Email to " + user.getEmail() + " not sent: email not confirmed.");
        }
        String to = user.getEmail();
//        , String from, String subject, String text
    }

    /* ----- VALIDATIONS ----- */

    private void validateDates(LocalDate startingDate, LocalDate endDate) throws BudgetDatesException {
        if (!startingDate.isBefore(endDate) || startingDate.isBefore(LocalDate.now())) {
            throw new BudgetDatesException();
        }
    }

    private void validateBudgetOwnership(Budget budget, long sessionUserId) throws MyException {
        if (budget == null){
            throw new BudgetNotFoundException();
        } else if (budget.getUserId() != sessionUserId) {
            throw new NotLoggedInException("User does not match budget.");
        }
    }

    /* ----- TESTING ----- */
//    @GetMapping(value = "testDate")
//    public dto1 testDate() {
//        return new dto1(LocalDate.now());
//    }
//    @AllArgsConstructor
//    @Getter
//    @Setter
//    static class dto1 {
//        LocalDate ld;
//    }
//    @GetMapping(value = "testString")
//    public dto2 testString() {
//        return new dto2("test test");
//    }
//    @AllArgsConstructor
//    @Getter
//    @Setter
//    static class dto2 {
//        String string;
//    }
//    @GetMapping(value = "testInt")
//    public dto3 testInteger() {
//        return new dto3(new Integer(5));
//    }
//    @AllArgsConstructor
//    @Getter
//    @Setter
//    static class dto3 {
//        Integer integer;
//    }
    /* ----- /TESTING ----- */
}