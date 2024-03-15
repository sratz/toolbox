/*
 * Copyright (c) 2023-2024 Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */
package eu.maveniverse.maven.toolbox.shared;

/**
 * Generic resolution scope abstraction.
 * <p>
 * Uses Maven3 mojo resolution scopes as template.
 */
public enum ResolutionScope {
    NONE,
    COMPILE,
    COMPILE_PLUS_RUNTIME,
    RUNTIME,
    RUNTIME_PLUS_SYSTEM,
    TEST;
}
