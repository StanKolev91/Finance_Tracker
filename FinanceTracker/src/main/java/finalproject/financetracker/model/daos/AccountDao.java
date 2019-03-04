package finalproject.financetracker.model.daos;

import com.mysql.cj.MysqlConnection;
import com.mysql.cj.jdbc.MysqlConnectionPoolDataSource;
import com.mysql.cj.jdbc.MysqlDataSource;
import com.mysql.cj.jdbc.MysqlPooledConnection;
import finalproject.financetracker.model.dtos.account.EditAccountDTO;
import finalproject.financetracker.model.pojos.Account;
import finalproject.financetracker.model.exceptions.NotFoundException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.sql.*;
import java.util.ArrayList;
import java.util.concurrent.locks.ReentrantLock;

@Component
public class AccountDao extends AbstractDao {

    public static final int QUERY_RETURN_MAX_LIMIT = 1000;
    public static final int QUERY_RETURN_LIMIT_DEFAULT = 20;
    public static final int QUERY_RETURN_OFFSET_DEFAULT = 0;
    private Connection mySQLCon;
    private final JdbcTemplate jdbcTemplate;
    private static final ReentrantLock connLock = new ReentrantLock();

    @Autowired
    AccountDao(JdbcTemplate jdbcTemplate) throws SQLException {
        this.jdbcTemplate = jdbcTemplate;
        checkConnAndReInitIfBad();
    }

    private void checkConnAndReInitIfBad( ) throws SQLException {
        synchronized (AccountDao.connLock) {
            if (this.mySQLCon == null || this.mySQLCon.isClosed()) {
                logger.info("MySQLCon in AccountDao is created.");
                this.mySQLCon = this.jdbcTemplate.getDataSource().getConnection();
            }
        }
    }

    @PreDestroy
    void closeMySQLCon() throws SQLException {
        try {
            if (this.mySQLCon!= null) {
                this.mySQLCon.close();
                logger.info("MySQLCon in AccountDao is closed.");
            }
        } catch (SQLException e) {
            logger.error("Error closing mySQLCon in AccountDao. Trying again...");
            this.mySQLCon.close();
            logger.info("MySQLCon in AccountDao is closed.");
        }
    }

    public void updateAcc(EditAccountDTO acc) throws SQLException {
        PreparedStatement ps = null;
        checkConnAndReInitIfBad();
        try {
            String sql = "UPDATE final_project.accounts SET account_name = ? WHERE account_id = ?;";
            ps = mySQLCon.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, acc.getAccountName().trim());
            ps.setLong(2, acc.getAccountId());
            ps.executeUpdate();
        }
        finally {
            closeStatement(ps);
        }
    }

    public void updateAccAmount(double amount, long accId) throws SQLException {
        PreparedStatement ps = null;
        checkConnAndReInitIfBad();
        try {
            String sql = "UPDATE final_project.accounts SET amount = ? WHERE account_id = ?;";
            ps = mySQLCon.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setDouble(1, amount);
            ps.setLong(2, accId);
            ps.executeUpdate();
        }
        finally {
            closeStatement(ps);
        }
    }

    public int getAllCount(long userId) throws SQLException {
        PreparedStatement ps = null;
        ResultSet rs = null;
        checkConnAndReInitIfBad();
        int result;
        try {
            String sql = "SELECT COUNT(*) AS count " +
                    "FROM final_project.accounts AS a " +
                    "WHERE a.user_id  = ?;";

            ps = mySQLCon.prepareStatement(sql);
            ps.setLong(1, userId);
            rs = ps.executeQuery();
            result = -1;

            if (rs.next()) {
                result = rs.getInt(1);
            }
        }
        finally {
            closeResultSet(rs);
            closeStatement(ps);
        }
        if (result<0) throw new SQLException("error retrieving ResultSet");
        return result;
    }

    public Account[] getAllAccountsAsc(long userId) throws SQLException {
        return getAll(userId, QUERY_RETURN_MAX_LIMIT, QUERY_RETURN_OFFSET_DEFAULT, SQLOderBy.ASC);
    }

    public Account[] getAllAccountsDesc(long userId) throws SQLException {
        return getAll(userId, QUERY_RETURN_MAX_LIMIT, QUERY_RETURN_OFFSET_DEFAULT, SQLOderBy.DESC);
    }

    private Account[] getAll(long userId, int limit, int offset, SQLOderBy order) throws SQLException {
        return getAllWhere(SQLColumnName.USER_ID, SQLCompareOperator.EQUALS, userId, limit, offset, order);
    }

    private Account[] getAllWhere(SQLColumnName param, SQLCompareOperator operator, long idColumnValueLong, int limit, int offset, SQLOderBy order) throws SQLException {
        PreparedStatement ps = null;
        ResultSet rs = null;
        checkConnAndReInitIfBad();
        ArrayList<Account> arr;
        try {
            String sql =
                    "SELECT a.account_id, a.account_name, a.amount, a.user_id " +
                            "FROM final_project.accounts AS a " +
                            "WHERE a." + param.toString() + " " + operator.getValue() + " ? ORDER BY a.account_name " + order + " LIMIT ? OFFSET ?;";

            ps = mySQLCon.prepareStatement(sql);
            ps.setLong(1, idColumnValueLong);
            ps.setInt(2, limit);
            ps.setInt(3, offset);
            rs = ps.executeQuery();
            arr = new ArrayList<>();

            while (rs.next()) {
                arr.add(new Account(
                        rs.getLong("account_id"),
                        rs.getString("account_name"),
                        rs.getDouble("amount"),
                        rs.getLong("user_id"))
                );
            }
        }
        finally {
            closeStatement(ps);
            closeResultSet(rs);
        }
        return arr.toArray(new Account[0]);
    }

    public long addAcc(Account acc) throws SQLException {
        PreparedStatement ps = null;
        ResultSet rs = null;
        checkConnAndReInitIfBad();
        long accId;
        try {
            String sql = "INSERT INTO final_project.accounts(account_name, user_id, amount) VALUES (?, ?, ?);";
            ps = mySQLCon.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, acc.getAccountName().trim());
            ps.setLong(2, acc.getUserId());
            ps.setDouble(3, acc.getAmount());
            ps.executeUpdate();
            rs = ps.getGeneratedKeys();
            accId = -1;

            if (rs.next()){
                accId = rs.getLong(1);
            }
        }
        finally {
            closeResultSet(rs);
            closeStatement(ps);
        }
        if (accId<=0) {
            throw new SQLException("error retrieving data from data base");
        }
        return accId;
    }

    public int deleteAcc(SQLColumnName param, SQLCompareOperator operator, long idColumn) throws SQLException {  //TODO delete all transactions from this account in a transactional DML query
        PreparedStatement ps = null;
        int affectedRows;
        checkConnAndReInitIfBad();
        try {
            String sql =
                    "DELETE a FROM final_project.accounts AS a " +
                            "WHERE a." + param.toString() + " " + operator.getValue() + " ?;";

            ps = mySQLCon.prepareStatement(sql);
            ps.setLong(1, idColumn);
            affectedRows = ps.executeUpdate();
        } finally {
            closeStatement(ps);
        }
        return affectedRows;
    }

    public Account getById(long id) throws SQLException, NotFoundException {
        PreparedStatement ps = null;
        ResultSet rs = null;
        checkConnAndReInitIfBad();
        try {
            String sql =
                    "SELECT a.account_id, a.account_name, a.amount, a.user_id " +
                            "FROM final_project.accounts AS a " +
                            "WHERE a.account_id = ?;";

            ps = mySQLCon.prepareStatement(sql);
            ps.setLong(1, id);
            rs = ps.executeQuery();

            if (rs.next()) {
                return new Account(
                        rs.getLong("account_id"),
                        rs.getString("account_name"),
                        rs.getDouble("amount"),
                        rs.getLong("user_id"));
            }
        }
        finally {
            closeResultSet(rs);
            closeStatement(ps);
        }
        return null;
    }

    public double getUserBalanceByUserId(long userId) throws SQLException {
        String sql = "SELECT SUM(a.amount) AS sum FROM final_project.accounts AS a WHERE a.user_id = ?";
        PreparedStatement ps = null;
        ResultSet rs = null;
        checkConnAndReInitIfBad();
        try {
            ps = mySQLCon.prepareStatement(sql);
            ps.setLong(1, userId);
            rs = ps.executeQuery();
            if (rs.next()){
                return rs.getDouble("sum");
            }else return 0;
        } finally {
            closeResultSet(rs);
            closeStatement(ps);
        }
    }
}
