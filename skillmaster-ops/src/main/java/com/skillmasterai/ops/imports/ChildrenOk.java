package com.skillmasterai.ops.imports;

import java.util.List;

/** The direct subdirectories a source listed, hidden ones already removed. */
public record ChildrenOk(List<String> names) implements ChildrenResult {

    public ChildrenOk {
        names = List.copyOf(names);
    }
}
