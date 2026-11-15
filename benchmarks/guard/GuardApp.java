import todo.TodoApp;

/** Starts the Jolt todo benchmark app on the port given as the first argument. */
public class GuardApp {
    public static void main(String[] args) {
        TodoApp.create().listen(Integer.parseInt(args[0]));
    }
}
