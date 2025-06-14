package in.jolt;

public class AttendanceApp {

    public static void start() {
        Jolt.GET("/", (req,res) -> {
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
            msg.add("message","Login Successful!");
            res.setBody(msg);
        });
        Jolt.GET("/login", (req,res) -> {
            res.setBody(new html("src/main/resources/login.html"));
        });
        Jolt.listen(5000, () -> {
            System.out.println("The Server is Running...");
        });
        added code 

    }
}
