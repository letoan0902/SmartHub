package vn.rikkeiexpress.smarthub.mcp;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Lop chan an toan cho tool chay SQL do AI sinh ra: chi cho SELECT mot statement,
 * chan tu khoa ghi/xoa du lieu va ep LIMIT de khong keo ca bang.
 */
@Component
public class SafeSqlValidator {

    private static final List<String> FORBIDDEN_KEYWORDS = List.of(
            "DROP", "DELETE", "UPDATE", "INSERT", "ALTER",
            "TRUNCATE", "CREATE", "GRANT", "REVOKE", "EXECUTE");

    /**
     * Tra ve null neu cau SQL hop le, nguoc lai tra ve chuoi ly do tu choi.
     */
    public String validate(String sql) {
        if (sql == null || sql.isBlank()) {
            return "Câu truy vấn rỗng, hãy cung cấp một câu SELECT.";
        }
        String normalized = sql.strip().toUpperCase();
        if (!normalized.startsWith("SELECT")) {
            return "Chỉ chấp nhận câu truy vấn bắt đầu bằng SELECT.";
        }
        String withoutTrailingSemicolon = normalized.endsWith(";")
                ? normalized.substring(0, normalized.length() - 1)
                : normalized;
        if (withoutTrailingSemicolon.contains(";")) {
            return "Không chấp nhận nhiều câu lệnh trong một truy vấn (phát hiện dấu ';' ở giữa).";
        }
        for (String keyword : FORBIDDEN_KEYWORDS) {
            // \b bat tu nguyen: chan "DROP TABLE" nhung khong chan cot ten kieu "created_at"
            if (Pattern.compile("\\b" + keyword + "\\b").matcher(normalized).find()) {
                return "Truy vấn chứa từ khóa bị cấm: " + keyword + ". Chỉ được phép đọc dữ liệu bằng SELECT.";
            }
        }
        return null;
    }

    /**
     * Ep LIMIT de AI khong keo ca bang ve lam tran ket qua tra cho model.
     */
    public String enforceLimit(String sql) {
        String stripped = sql.strip();
        if (stripped.endsWith(";")) {
            stripped = stripped.substring(0, stripped.length() - 1);
        }
        if (stripped.toUpperCase().contains("LIMIT")) {
            return stripped;
        }
        return stripped + " LIMIT 100";
    }
}
