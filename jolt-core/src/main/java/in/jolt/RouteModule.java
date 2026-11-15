package in.jolt;

/** A unit of routes built from its own collaborators. Mount it with {@link Router#module}. */
@FunctionalInterface
public interface RouteModule {
    void register(Router router);
}
