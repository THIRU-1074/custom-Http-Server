package todo;

import jakarta.validation.constraints.NotBlank;

public record TodoInput(@NotBlank String title, boolean done) {}
