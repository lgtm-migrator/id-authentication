package io.mosip.kernel.keymanagerservice.service.impl;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.mosip.kernel.core.crypto.exception.InvalidDataException;
import io.mosip.kernel.core.crypto.exception.InvalidKeyException;
import io.mosip.kernel.core.crypto.exception.NullDataException;
import io.mosip.kernel.core.crypto.exception.NullKeyException;
import io.mosip.kernel.core.crypto.exception.NullMethodException;
import io.mosip.kernel.core.crypto.spi.Decryptor;
import io.mosip.kernel.core.keymanager.spi.KeyStore;
import io.mosip.kernel.keygenerator.bouncycastle.KeyGenerator;
import io.mosip.kernel.keymanagerservice.constant.KeymanagerErrorConstants;
import io.mosip.kernel.keymanagerservice.dto.PublicKeyResponse;
import io.mosip.kernel.keymanagerservice.dto.SymmetricKeyRequestDto;
import io.mosip.kernel.keymanagerservice.dto.SymmetricKeyResponseDto;
import io.mosip.kernel.keymanagerservice.entity.KeyAlias;
import io.mosip.kernel.keymanagerservice.entity.KeyDbStore;
import io.mosip.kernel.keymanagerservice.entity.KeyPolicy;
import io.mosip.kernel.keymanagerservice.exception.CryptoException;
import io.mosip.kernel.keymanagerservice.exception.InvalidApplicationIdException;
import io.mosip.kernel.keymanagerservice.exception.NoUniqueAliasException;
import io.mosip.kernel.keymanagerservice.repository.KeyAliasRepository;
import io.mosip.kernel.keymanagerservice.repository.KeyPolicyRepository;
import io.mosip.kernel.keymanagerservice.repository.KeyStoreRepository;
import io.mosip.kernel.keymanagerservice.service.KeymanagerService;
import io.mosip.kernel.keymanagerservice.util.KeymanagerUtil;

/**
 * This class provides the implementation for the methods of KeymanagerService
 * interface.
 *
 * @author Dharmesh Khandelwal
 * @since 1.0.0
 *
 */
@Service
@Transactional
public class KeymanagerServiceImpl implements KeymanagerService {

	/**
	 * Keystore to handles and store cryptographic keys.
	 */
	@Autowired
	KeyStore keyStore;

	/**
	 * KeyGenerator instance to generate asymmetric key pairs
	 */
	@Autowired
	KeyGenerator keyGenerator;

	/**
	 * Decryptor instance to decrypt data
	 */
	@Autowired
	Decryptor<PrivateKey, PublicKey, SecretKey> decryptor;

	/**
	 * 
	 */
	@Autowired
	KeyAliasRepository keyAliasRepository;

	/**
	 * 
	 */
	@Autowired
	KeyPolicyRepository keyPolicyRepository;

	/**
	 * 
	 */
	@Autowired
	KeyStoreRepository keyStoreRepository;

	/**
	 * Utility to generate Metadata
	 */
	@Autowired
	KeymanagerUtil keymanagerUtil;

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * io.mosip.kernel.keymanager.service.KeymanagerService#getPublicKey(java.lang.
	 * String, java.time.LocalDateTime, java.util.Optional)
	 */
	@Override
	public PublicKeyResponse<String> getPublicKey(String applicationId, LocalDateTime timeStamp,
			Optional<String> referenceId) {

		PublicKeyResponse<String> publicKeyResponse = new PublicKeyResponse<>();
		if (!referenceId.isPresent() || referenceId.get().trim().isEmpty()) {
			PublicKeyResponse<PublicKey> hsmPublicKey = getPublicKeyFromHSM(applicationId, timeStamp);
			publicKeyResponse.setPublicKey(keymanagerUtil.encodeBase64(hsmPublicKey.getPublicKey().getEncoded()));
			publicKeyResponse.setKeyGenerationTime(hsmPublicKey.getKeyGenerationTime());
			publicKeyResponse.setKeyExpiryTime(hsmPublicKey.getKeyExpiryTime());
		} else {
			PublicKeyResponse<byte[]> dbPublicKey = getPublicKeyFromDBStore(applicationId, timeStamp,
					referenceId.get());
			publicKeyResponse.setPublicKey(keymanagerUtil.encodeBase64(dbPublicKey.getPublicKey()));
			publicKeyResponse.setKeyGenerationTime(dbPublicKey.getKeyGenerationTime());
			publicKeyResponse.setKeyExpiryTime(dbPublicKey.getKeyExpiryTime());
		}
		return publicKeyResponse;
	}

	/**
	 * @param applicationId
	 * @param timeStamp
	 * @param referenceId
	 * @param alias
	 * @param keyResponseDto
	 * @return
	 * @throws NoUniqueAliasException
	 * @throws InvalidApplicationIdException
	 */
	private PublicKeyResponse<PublicKey> getPublicKeyFromHSM(String applicationId, LocalDateTime timeStamp) {

		String alias = null;
		LocalDateTime generationDateTime = null;
		LocalDateTime expiryDateTime = null;
		List<KeyAlias> currentKeyAlias = getCurrentKeyAlias(applicationId, null, timeStamp);

		if (currentKeyAlias.size() > 1) {
			throw new NoUniqueAliasException(KeymanagerErrorConstants.NO_UNIQUE_ALIAS.getErrorCode(),
					KeymanagerErrorConstants.NO_UNIQUE_ALIAS.getErrorMessage());
		} else if (currentKeyAlias.size() == 1) {
			KeyAlias fetchedKeyAlias = currentKeyAlias.get(0);
			alias = fetchedKeyAlias.getAlias();
			generationDateTime = fetchedKeyAlias.getKeyGenerationTime();
			expiryDateTime = fetchedKeyAlias.getKeyExpiryTime();
		} else if (currentKeyAlias.isEmpty()) {
			alias = UUID.randomUUID().toString();
			generationDateTime = timeStamp;
			expiryDateTime = getExpiryPolicy(applicationId, generationDateTime);
			keyStore.storeAsymmetricKey(keyGenerator.getAsymmetricKey(), alias, generationDateTime, expiryDateTime);
			storeKeyInAlias(applicationId, generationDateTime, null, alias, expiryDateTime);
		}
		return new PublicKeyResponse<>(alias, keyStore.getPublicKey(alias), generationDateTime, expiryDateTime);
	}

	/**
	 * @param applicationId
	 * @param timeStamp
	 * @param referenceId
	 * @return
	 * @throws NoUniqueAliasException
	 */
	private PublicKeyResponse<byte[]> getPublicKeyFromDBStore(String applicationId, LocalDateTime timeStamp,
			String referenceId) {

		String alias = null;
		byte[] publicKey = null;
		LocalDateTime generationDateTime = null;
		LocalDateTime expiryDateTime = null;
		List<KeyAlias> currentKeyAlias = getCurrentKeyAlias(applicationId, referenceId, timeStamp);

		if (currentKeyAlias.size() > 1) {
			throw new NoUniqueAliasException(KeymanagerErrorConstants.NO_UNIQUE_ALIAS.getErrorCode(),
					KeymanagerErrorConstants.NO_UNIQUE_ALIAS.getErrorMessage());
		} else if (currentKeyAlias.size() == 1) {
			Optional<KeyDbStore> keyFromDBStore = keyStoreRepository.findByAlias(currentKeyAlias.get(0).getAlias());
			if (!keyFromDBStore.isPresent()) {
				throw new NoUniqueAliasException(KeymanagerErrorConstants.NO_UNIQUE_ALIAS.getErrorCode(),
						KeymanagerErrorConstants.NO_UNIQUE_ALIAS.getErrorMessage());
			} else {
				KeyAlias fetchedKeyAlias = currentKeyAlias.get(0);
				publicKey = keyFromDBStore.get().getPublicKey();
				generationDateTime = fetchedKeyAlias.getKeyGenerationTime();
				expiryDateTime = fetchedKeyAlias.getKeyExpiryTime();
			}
		} else if (currentKeyAlias.isEmpty()) {
			byte[] encryptedPrivateKey;
			alias = UUID.randomUUID().toString();
			KeyPair keypair = keyGenerator.getAsymmetricKey();
			PublicKeyResponse<PublicKey> hsmPublicKey = getPublicKeyFromHSM(applicationId, timeStamp);
			PublicKey masterPublicKey = hsmPublicKey.getPublicKey();
			String masterAlias = hsmPublicKey.getAlias();
			publicKey = keypair.getPublic().getEncoded();
			generationDateTime = timeStamp;
			expiryDateTime = getExpiryPolicy(applicationId, generationDateTime);
			try {
				encryptedPrivateKey = keymanagerUtil.encryptKey(keypair.getPrivate(), masterPublicKey);
			} catch (InvalidDataException | InvalidKeyException | NullDataException | NullKeyException
					| NullMethodException e) {
				throw new CryptoException(KeymanagerErrorConstants.CRYPTO_EXCEPTION.getErrorCode(),
						KeymanagerErrorConstants.CRYPTO_EXCEPTION.getErrorMessage());
			}
			storeKeyInDBStore(alias, masterAlias, keypair.getPublic().getEncoded(), encryptedPrivateKey);
			storeKeyInAlias(applicationId, generationDateTime, referenceId, alias, expiryDateTime);
		}

		return new PublicKeyResponse<>(alias, publicKey, generationDateTime, expiryDateTime);

	}

	/**
	 * @param applicationId
	 * @param string
	 * @param timeStamp
	 * @return
	 */
	private List<KeyAlias> getCurrentKeyAlias(String applicationId, String referenceId, LocalDateTime timeStamp) {
		return keyAliasRepository
				.findByApplicationIdAndReferenceId(applicationId, referenceId).stream().filter(
						keyAlias -> timeStamp.isEqual(keyAlias.getKeyGenerationTime())
								|| timeStamp.isEqual(keyAlias.getKeyExpiryTime())
								|| (timeStamp.isAfter(keyAlias.getKeyGenerationTime())
										&& timeStamp.isBefore(keyAlias.getKeyExpiryTime())))
				.collect(Collectors.toList());
	}

	/**
	 * @param applicationId
	 * @param timeStamp
	 * @return
	 * @throws InvalidApplicationIdException
	 */
	private LocalDateTime getExpiryPolicy(String applicationId, LocalDateTime timeStamp) {
		Optional<KeyPolicy> keyPolicy = keyPolicyRepository.findByApplicationId(applicationId);
		if (!keyPolicy.isPresent()) {
			throw new InvalidApplicationIdException(KeymanagerErrorConstants.APPLICATIONID_NOT_VALID.getErrorCode(),
					KeymanagerErrorConstants.APPLICATIONID_NOT_VALID.getErrorMessage());
		}
		return timeStamp.plusDays(keyPolicy.get().getValidityInDays());
	}

	/**
	 * @param applicationId
	 * @param timeStamp
	 * @param referenceId
	 * @param alias
	 * @param expiryDateTime
	 */
	private void storeKeyInAlias(String applicationId, LocalDateTime timeStamp, String referenceId, String alias,
			LocalDateTime expiryDateTime) {
		KeyAlias keyAlias = new KeyAlias();
		keyAlias.setAlias(alias);
		keyAlias.setApplicationId(applicationId);
		keyAlias.setReferenceId(referenceId);
		keyAlias.setKeyGenerationTime(timeStamp);
		keyAlias.setKeyExpiryTime(expiryDateTime);
		keyAliasRepository.save(keymanagerUtil.setMetaData(keyAlias));
	}

	/**
	 * @param alias
	 * @param bs
	 * @param encryptedPrivateKey
	 */
	private void storeKeyInDBStore(String alias, String masterAlias, byte[] publicKey, byte[] encryptedPrivateKey) {
		KeyDbStore keyDbStore = new KeyDbStore();
		keyDbStore.setAlias(alias);
		keyDbStore.setMasterAlias(masterAlias);
		keyDbStore.setPublicKey(publicKey);
		keyDbStore.setPrivateKey(encryptedPrivateKey);
		keyStoreRepository.save(keymanagerUtil.setMetaData(keyDbStore));
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * io.mosip.kernel.keymanager.service.KeymanagerService#decryptSymmetricKey(java
	 * .lang.String, java.time.LocalDateTime, java.util.Optional, byte[])
	 */
	@Override
	public SymmetricKeyResponseDto decryptSymmetricKey(SymmetricKeyRequestDto symmetricKeyRequestDto) {

		List<KeyAlias> currentKeyAlias;
		LocalDateTime timeStamp = symmetricKeyRequestDto.getTimeStamp();
		String referenceId = symmetricKeyRequestDto.getReferenceId();
		String applicationId = symmetricKeyRequestDto.getApplicationId();
		SymmetricKeyResponseDto keyResponseDto = new SymmetricKeyResponseDto();

		if (referenceId == null || referenceId.trim().isEmpty()) {
			currentKeyAlias = getCurrentKeyAlias(applicationId, null, timeStamp);
		} else {
			currentKeyAlias = getCurrentKeyAlias(applicationId, referenceId, timeStamp);
		}

		if (currentKeyAlias.isEmpty() || currentKeyAlias.size() > 1) {
			throw new NoUniqueAliasException(KeymanagerErrorConstants.NO_UNIQUE_ALIAS.getErrorCode(),
					KeymanagerErrorConstants.NO_UNIQUE_ALIAS.getErrorMessage());
		} else if (currentKeyAlias.size() == 1) {
			KeyAlias fetchedKeyAlias = currentKeyAlias.get(0);
			PrivateKey privateKey = getPrivateKey(referenceId, fetchedKeyAlias);
			byte[] decryptedSymmetricKey = decryptor.asymmetricPrivateDecrypt(privateKey,
					keymanagerUtil.decodeBase64(symmetricKeyRequestDto.getEncryptedSymmetricKey()));
			keyResponseDto.setSymmetricKey(keymanagerUtil.encodeBase64(decryptedSymmetricKey));
		}
		return keyResponseDto;
	}

	/**
	 * @param privateKey
	 * @param referenceId
	 * @param fetchedKeyAlias
	 * @return
	 * @throws CryptoException
	 */
	private PrivateKey getPrivateKey(String referenceId, KeyAlias fetchedKeyAlias) {

		if (referenceId == null || referenceId.trim().isEmpty()) {
			return keyStore.getPrivateKey(fetchedKeyAlias.getAlias());
		} else {
			Optional<KeyDbStore> keyDbStore = keyStoreRepository.findByAlias(fetchedKeyAlias.getAlias());
			if (!keyDbStore.isPresent()) {
				throw new NoUniqueAliasException(KeymanagerErrorConstants.NO_UNIQUE_ALIAS.getErrorCode(),
						KeymanagerErrorConstants.NO_UNIQUE_ALIAS.getErrorMessage());
			}
			PrivateKey masterPrivateKey = keyStore.getPrivateKey(keyDbStore.get().getMasterAlias());
			try {
				byte[] decryptedPrivateKey = keymanagerUtil.decryptKey(keyDbStore.get().getPrivateKey(),
						masterPrivateKey);
				return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(decryptedPrivateKey));
			} catch (InvalidDataException | InvalidKeyException | NullDataException | NullKeyException
					| NullMethodException | InvalidKeySpecException | NoSuchAlgorithmException e) {
				throw new CryptoException(KeymanagerErrorConstants.CRYPTO_EXCEPTION.getErrorCode(),
						KeymanagerErrorConstants.CRYPTO_EXCEPTION.getErrorMessage());
			}
		}
	}
}
