package in.jolt;
import java.sql.*;
public class AttendanceApp {
    public static boolean authenticate(String userId, String password) {
        String sql = "SELECT 1 FROM credentials WHERE user_id = ? AND password = ?";

        try {
            Connection conn = DBConnection.getConnection();
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, userId);
                stmt.setString(2, password);

                try (ResultSet rs = stmt.executeQuery()) {
                    return rs.next();  // true if found
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }
    public static void start(int port) {
        Jolt.GET("/", (req,res) -> {
            try{
            Jolt.authorizeClient(req,res,"Site-Access");
            }
            catch(UnauthorizedException e){
                e.printStackTrace();
            }
            res.setBody(new html("src/main/resources/index.html"));
        });
        Jolt.GET("/favicon.ico", (req,res) -> {
            res.setBody(new image("src/main/resources/image.png"));
        });
        Jolt.GET("/getClassesInfo", (req,res) -> {
            JSON localObJson = new JSON();
            localObJson.add("Name", "RegNo");
            localObJson.add("Thiru", "22BEC1473");
        });
        Jolt.POST("/login", (req,res) -> {
            JSON localObJson =(JSON)req.getBody();
            String userName = (String) localObJson.get("user_name");
            String password = (String) localObJson.get("password");
            JSON msg=new JSON();
            if(authenticate(userName, password)){
            msg.add("message","Login Successful!");
            }
           else{
            msg.add("message","UserName or Password incorrect...");
           } 
            res.setBody(msg);
        });
        Jolt.GET("/login", (req,res) -> {
            res.setBody(new html("src/main/resources/login.html"));
        });
        Jolt.listen(port, () -> {
            System.out.println("The Server is Running at port "+port+"...");
        });
    }
}
