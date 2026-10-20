package com.bloodlink.web.error;

/**
 * A phone that cannot be converted to the stored format (PhoneNumber.normalize): a 400 on "phone",
 * even at login, since it describes what was typed, not whether an account exists.
 */
public class InvalidPhoneNumberException extends RuntimeException {

    public InvalidPhoneNumberException(String message) {
        super(message);
    }
}
