package com.wbank.platform.error;

import java.util.UUID;

/** A referenced entity does not exist. Maps to HTTP 404. */
public class NotFoundException extends DomainException {

    public NotFoundException(String errorCode, String message) {
        super(errorCode, message);
    }

    public static NotFoundException of(String entity, UUID id) {
        return new NotFoundException(entity + ".not_found", "%s %s does not exist".formatted(entity, id));
    }
}
