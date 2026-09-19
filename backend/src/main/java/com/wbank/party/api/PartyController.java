package com.wbank.party.api;

import com.wbank.party.PartyService;
import com.wbank.party.domain.Organization;
import com.wbank.party.domain.Party;
import com.wbank.party.domain.Person;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/parties")
public class PartyController {

    private final PartyService parties;

    public PartyController(PartyService parties) {
        this.parties = parties;
    }

    public record RegisterPersonRequest(@NotBlank String givenName, @NotBlank String familyName,
                                        @NotNull LocalDate dateOfBirth, @NotBlank String countryCode) {}

    public record RegisterOrganizationRequest(@NotBlank String legalName, String displayName,
                                              @NotBlank String registrationNumber, @NotBlank String countryCode) {}

    public record PartyResponse(UUID id, String partyType, String legalName, String displayName,
                                String countryCode, String givenName, String familyName, LocalDate dateOfBirth,
                                String registrationNumber) {
        public static PartyResponse from(Party p) {
            if (p instanceof Person person) {
                return new PartyResponse(p.getId(), p.type().name(), p.getLegalName(), p.getDisplayName(),
                        p.getCountryCode(), person.getGivenName(), person.getFamilyName(),
                        person.getDateOfBirth(), null);
            }
            Organization org = (Organization) p;
            return new PartyResponse(p.getId(), p.type().name(), p.getLegalName(), p.getDisplayName(),
                    p.getCountryCode(), null, null, null, org.getRegistrationNumber());
        }
    }

    @PostMapping("/persons")
    public ResponseEntity<PartyResponse> registerPerson(@Valid @RequestBody RegisterPersonRequest r) {
        return ResponseEntity.status(HttpStatus.CREATED).body(PartyResponse.from(
                parties.registerPerson(r.givenName(), r.familyName(), r.dateOfBirth(), r.countryCode())));
    }

    @PostMapping("/organizations")
    public ResponseEntity<PartyResponse> registerOrganization(@Valid @RequestBody RegisterOrganizationRequest r) {
        return ResponseEntity.status(HttpStatus.CREATED).body(PartyResponse.from(
                parties.registerOrganization(r.legalName(), r.displayName(), r.registrationNumber(),
                        r.countryCode())));
    }

    @GetMapping("/{id}")
    public PartyResponse get(@PathVariable UUID id) {
        return PartyResponse.from(parties.require(id));
    }
}
