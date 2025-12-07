package dk.panos.promofacie.v2;

import dk.panos.promofacie.radix.RadixClient;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.StructuredTaskScope;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@ApplicationScoped
public class RadixService {

    @RestClient
    RadixClient gatewayClient;

    public List<String> getAllNFTsForAddress(String address, String resourceAddress) {
        List<String> allNfts = new ArrayList<>();
        String cursor = null;

        do {
            EntityDetailsRequest request = new EntityDetailsRequest(
                    List.of(address),
                    "Vault",
                    new EntityDetailsRequest.OptIns(true),
                    cursor
            );

            EntityDetailsResponse response = gatewayClient.getEntityDetails(request);

            // Extract NFTs from this page
            List<String> nftsInPage = extractNFTsFromResponse(response, resourceAddress);
            allNfts.addAll(nftsInPage);

            // Get next cursor for pagination
            cursor = response.nextCursor();

        } while (cursor != null && !cursor.isEmpty());

        return allNfts;
    }

    /**
     * Fetches NFTs for multiple addresses in batches of 20 (API limit)
     * Uses structured concurrency to fetch batches in parallel
     */
    public Map<String, List<String>> getAllNFTsForAddresses(List<String> addresses, String resourceAddress) {
        if (addresses.isEmpty()) {
            return Map.of();
        }

        // Batch addresses into chunks of 20 (API limit)
        List<List<String>> batches = new ArrayList<>();
        for (int i = 0; i < addresses.size(); i += 20) {
            batches.add(addresses.subList(i, Math.min(i + 20, addresses.size())));
        }

        // Use structured concurrency to fetch all batches in parallel
        try (StructuredTaskScope.ShutdownOnFailure scope = new StructuredTaskScope.ShutdownOnFailure()) {
            List<StructuredTaskScope.Subtask<Map<String, List<String>>>> futures = batches.stream()
                    .map(batch -> scope.fork(() -> fetchNFTsForBatch(batch, resourceAddress)))
                    .toList();

            scope.join();
            scope.throwIfFailed();

            // Combine all results
            return futures.stream()
                    .map(StructuredTaskScope.Subtask::get)
                    .flatMap(map -> map.entrySet().stream())
                    .collect(Collectors.toMap(
                            Map.Entry::getKey,
                            Map.Entry::getValue,
                            (v1, v2) -> v1
                    ));

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while fetching NFTs for multiple addresses", e);
        } catch (ExecutionException e) {
            throw new RuntimeException("Failed to fetch NFTs for addresses", e.getCause());
        }
    }

    private Map<String, List<String>> fetchNFTsForBatch(List<String> addresses, String resourceAddress) {
        Map<String, List<String>> result = new HashMap<>();
        String cursor = null;

        do {
            EntityDetailsRequest request = new EntityDetailsRequest(
                    addresses,
                    "Vault",
                    new EntityDetailsRequest.OptIns(true),
                    cursor
            );

            EntityDetailsResponse response = gatewayClient.getEntityDetails(request);

            // Extract NFTs for each address in the batch
            if (response.items() != null) {
                for (EntityDetailsResponse.EntityItem entity : response.items()) {
                    List<String> nfts = extractNFTsFromEntity(entity, resourceAddress);
                    result.computeIfAbsent(entity.address(), k -> new ArrayList<>()).addAll(nfts);
                }
            }

            cursor = response.nextCursor();

        } while (cursor != null && !cursor.isEmpty());

        return result;
    }

    private List<String> extractNFTsFromEntity(EntityDetailsResponse.EntityItem entity, String resourceAddress) {
        if (entity.nonFungibleResources() == null) {
            return List.of();
        }

        return entity.nonFungibleResources().items().stream()
                .filter(r -> r.resourceAddress().equals(resourceAddress))
                .flatMap(r -> r.vaults().items().stream())
                .flatMap(v -> v.items() != null ? v.items().stream() : Stream.empty())
                .collect(Collectors.toList());
    }

    private List<String> extractNFTsFromResponse(EntityDetailsResponse response, String resourceAddress) {
        if (response.items() == null || response.items().isEmpty()) {
            return List.of();
        }

        EntityDetailsResponse.EntityItem entity = response.items().get(0);
        return extractNFTsFromEntity(entity, resourceAddress);
    }

    /**
     * Fetches metadata for NFTs in batches using structured concurrency
     * All batches run concurrently on virtual threads
     */
    public List<NFTMetadata> getNFTMetadata(String resourceAddress, List<String> nftIds) {
        if (nftIds.isEmpty()) {
            return List.of();
        }

        // Batch NFT IDs into chunks of 100 (API limit)
        List<List<String>> batches = new ArrayList<>();
        for (int i = 0; i < nftIds.size(); i += 100) {
            batches.add(nftIds.subList(i, Math.min(i + 100, nftIds.size())));
        }

        // Use structured concurrency to fetch all batches in parallel
        try (StructuredTaskScope.ShutdownOnFailure scope = new StructuredTaskScope.ShutdownOnFailure()) {
            List<StructuredTaskScope.Subtask<List<NFTMetadata>>> futures = batches.stream()
                    .map(batch -> scope.fork(() -> fetchMetadataBatch(resourceAddress, batch)))
                    .toList();

            scope.join();
            scope.throwIfFailed();

            // Combine all results
            return futures.stream()
                    .map(StructuredTaskScope.Subtask::get)
                    .flatMap(List::stream)
                    .collect(Collectors.toList());

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while fetching NFT metadata", e);
        } catch (ExecutionException e) {
            throw new RuntimeException("Failed to fetch NFT metadata", e.getCause());
        }
    }

    private List<NFTMetadata> fetchMetadataBatch(String resourceAddress, List<String> nftIds) {
        NFTDataRequest request = new NFTDataRequest(resourceAddress, nftIds);
        NFTDataResponse response = gatewayClient.getNFTData(request);

        return response.nonFungibleIds().stream()
                .map(this::parseNFTMetadata)
                .collect(Collectors.toList());
    }

    private NFTMetadata parseNFTMetadata(NFTDataResponse.NFTData data) {
        Map<String, String> traits = new HashMap<>();
        String name = data.id();

        if (data.data() != null) {
            // Parse traits from data map
            Object traitsData = data.data().get("traits");
            if (traitsData instanceof Map<?, ?> traitsMap) {
                traitsMap.forEach((k, v) ->
                        traits.put(k.toString(), v.toString())
                );
            }

            Object nameData = data.data().get("name");
            if (nameData != null) {
                name = nameData.toString();
            }
        }

        return new NFTMetadata(data.id(), traits, name);
    }
}