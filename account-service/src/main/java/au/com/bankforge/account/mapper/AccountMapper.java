package au.com.bankforge.account.mapper;

import au.com.bankforge.account.dto.AccountDto;
import au.com.bankforge.account.dto.CreateAccountResponse;
import au.com.bankforge.account.entity.Account;
import org.mapstruct.Mapper;

/**
 * MapStruct mapper for Account entity to DTO conversions.
 *
 * componentModel = "spring" — generates a @Component bean, injectable via @Autowired.
 * Compile-time code generation — zero reflection at runtime.
 */
@Mapper(componentModel = "spring")
public interface AccountMapper {

    /**
     * Maps Account entity to CreateAccountResponse DTO.
     * Used after account creation to return the 201 response body.
     */
    CreateAccountResponse toCreateResponse(Account account);

    /**
     * Maps Account entity to AccountDto.
     * Used for GET /api/accounts/{id} responses.
     */
    AccountDto toDto(Account account);
}
