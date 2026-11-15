package in.jolt.examples.todos.domain;

/** A to-do item. Id 0 means not yet stored. */
public record Todo(long id, String title, boolean done) {}
