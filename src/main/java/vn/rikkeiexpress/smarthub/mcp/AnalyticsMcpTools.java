package vn.rikkeiexpress.smarthub.mcp;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Bo tool doi soat du lieu van hanh, expose qua MCP server (profile mcp).
 * Ca 3 tool KHONG throw: moi loi tra ve chuoi thong bao de model tu dien giai.
 */
@Component
public class AnalyticsMcpTools {

    private final JdbcTemplate jdbcTemplate;
    private final SafeSqlValidator safeSqlValidator;

    public AnalyticsMcpTools(JdbcTemplate jdbcTemplate, SafeSqlValidator safeSqlValidator) {
        this.jdbcTemplate = jdbcTemplate;
        this.safeSqlValidator = safeSqlValidator;
    }

    @Tool(description = "Chạy một câu truy vấn SELECT an toàn trên hai bảng deliveries và incidents "
            + "để phân tích dữ liệu vận hành. Chỉ chấp nhận SELECT, tự động giới hạn 100 dòng.")
    public String runSafeQuery(String sql) {
        String rejection = safeSqlValidator.validate(sql);
        if (rejection != null) {
            return rejection;
        }
        try {
            String limited = safeSqlValidator.enforceLimit(sql);
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(limited);
            if (rows.isEmpty()) {
                return "Truy vấn hợp lệ nhưng không có dòng dữ liệu nào khớp.";
            }
            StringBuilder out = new StringBuilder("Kết quả (" + rows.size() + " dòng):\n");
            for (Map<String, Object> row : rows) {
                StringBuilder line = new StringBuilder("- ");
                row.forEach((col, val) -> line.append(col).append("=").append(val).append(" | "));
                out.append(line.substring(0, line.length() - 3)).append("\n");
            }
            return out.toString();
        } catch (Exception e) {
            return "Lỗi khi chạy truy vấn: " + e.getMessage();
        }
    }

    @Tool(description = "Thống kê hiệu suất một bưu cục: tổng số đơn, số đơn theo từng trạng thái "
            + "và số phiếu sự cố đang mở")
    public String getHubPerformance(String hubCode) {
        if (hubCode == null || hubCode.isBlank()) {
            return "Thiếu mã bưu cục, ví dụ HN-01, SG-02, DN-03.";
        }
        try {
            List<Map<String, Object>> statusCounts = jdbcTemplate.queryForList(
                    "SELECT status, COUNT(*) AS total FROM deliveries WHERE hub_code = ? GROUP BY status",
                    hubCode);
            Long openIncidents = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM incidents WHERE hub_code = ? AND status = 'OPEN'",
                    Long.class, hubCode);

            long totalDeliveries = statusCounts.stream()
                    .mapToLong(row -> ((Number) row.get("total")).longValue())
                    .sum();

            StringBuilder out = new StringBuilder("Hiệu suất bưu cục " + hubCode + ":\n");
            out.append("- Tổng số đơn: ").append(totalDeliveries).append("\n");
            for (Map<String, Object> row : statusCounts) {
                out.append("- Đơn trạng thái ").append(row.get("status"))
                        .append(": ").append(row.get("total")).append("\n");
            }
            out.append("- Phiếu sự cố đang mở (OPEN): ")
                    .append(openIncidents == null ? 0 : openIncidents);
            return out.toString();
        } catch (Exception e) {
            return "Lỗi khi thống kê bưu cục " + hubCode + ": " + e.getMessage();
        }
    }

    @Tool(description = "Xuất báo cáo Markdown hiệu suất bưu cục ra thư mục reports, "
            + "trả về đường dẫn file")
    public String exportHubReportMarkdown(String hubCode) {
        if (hubCode == null || hubCode.isBlank()) {
            return "Thiếu mã bưu cục, ví dụ HN-01, SG-02, DN-03.";
        }
        try {
            String performance = getHubPerformance(hubCode);

            List<Map<String, Object>> deliveries = jdbcTemplate.queryForList(
                    "SELECT tracking_code, customer_name, status, cod_amount, created_at "
                            + "FROM deliveries WHERE hub_code = ? ORDER BY tracking_code LIMIT 100",
                    hubCode);

            StringBuilder md = new StringBuilder();
            md.append("# Báo cáo hiệu suất bưu cục ").append(hubCode).append("\n\n");
            md.append("## Tổng quan\n\n").append(performance).append("\n\n");
            md.append("## Chi tiết đơn hàng\n\n");
            md.append("| Mã vận đơn | Khách hàng | Trạng thái | COD | Ngày tạo |\n");
            md.append("|---|---|---|---|---|\n");
            for (Map<String, Object> row : deliveries) {
                md.append("| ").append(row.get("tracking_code"))
                        .append(" | ").append(row.get("customer_name"))
                        .append(" | ").append(row.get("status"))
                        .append(" | ").append(row.get("cod_amount"))
                        .append(" | ").append(row.get("created_at"))
                        .append(" |\n");
            }

            Path reportsDir = Path.of("reports");
            Files.createDirectories(reportsDir);
            Path reportFile = reportsDir.resolve("bao-cao-" + hubCode + ".md");
            Files.writeString(reportFile, md.toString(), StandardCharsets.UTF_8);
            return "Đã xuất báo cáo: " + reportFile.toAbsolutePath();
        } catch (IOException e) {
            return "Lỗi ghi file báo cáo: " + e.getMessage();
        } catch (Exception e) {
            return "Lỗi khi xuất báo cáo bưu cục " + hubCode + ": " + e.getMessage();
        }
    }
}
