package com.wbank.party;

import com.wbank.party.domain.Party;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PartyRepository extends JpaRepository<Party, UUID> {
}
