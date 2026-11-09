import MapKit
import RainKit
import SwiftUI

/// Add a place by name search, the phone's location, or a pin on the map.
struct AddLocationView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var query = ""
    @State private var results: [GeocodingResult] = []
    @State private var searching = false
    @State private var locating = false
    @State private var message: String?

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Button {
                        Task { await addCurrentLocation() }
                    } label: {
                        HStack {
                            Label("Use my current location", systemImage: "location.fill")
                            if locating { Spacer(); ProgressView() }
                        }
                    }
                    .disabled(locating)
                    NavigationLink {
                        MapPickerView { name, coordinate in
                            model.add(name: name, coordinate: coordinate)
                            dismiss()
                        }
                    } label: {
                        Label("Pick on the map", systemImage: "map")
                    }
                }

                if let message {
                    Section { Text(message).foregroundStyle(.secondary) }
                }

                if !results.isEmpty {
                    Section("Results") {
                        ForEach(results) { result in
                            Button {
                                model.add(result)
                                dismiss()
                            } label: {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(result.name).foregroundStyle(.primary)
                                    if let detail = result.detail {
                                        Text(detail).font(.caption).foregroundStyle(.secondary)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            .overlay {
                if searching && results.isEmpty { ProgressView() }
            }
            .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always), prompt: "Search a city or place")
            .autocorrectionDisabled()
            .task(id: query) {
                // Debounce typing, then search.
                try? await Task.sleep(for: .milliseconds(350))
                guard !Task.isCancelled else { return }
                await search()
            }
            .navigationTitle("Add a place")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
        }
    }

    private func search() async {
        let text = query.trimmingCharacters(in: .whitespaces)
        guard text.count >= 2 else {
            results = []
            message = nil
            return
        }
        searching = true
        defer { searching = false }
        do {
            let language = Locale.current.language.languageCode?.identifier ?? "en"
            results = try await model.geocoder.search(text, language: language)
            message = results.isEmpty ? "No places found for “\(text)”." : nil
        } catch is CancellationError {
        } catch {
            message = "Search failed: \(error.localizedDescription)"
        }
    }

    private func addCurrentLocation() async {
        locating = true
        defer { locating = false }
        do {
            try await model.addCurrentLocation()
            dismiss()
        } catch {
            message = error.localizedDescription
        }
    }
}

/// Tap the map to drop a pin, name it, save.
struct MapPickerView: View {
    let onSave: (String, CLLocationCoordinate2D) -> Void

    @State private var position: MapCameraPosition = .userLocation(fallback: .automatic)
    @State private var pin: CLLocationCoordinate2D?
    @State private var name = ""

    var body: some View {
        MapReader { proxy in
            Map(position: $position) {
                UserAnnotation()
                if let pin {
                    Marker(name.isEmpty ? "Selected place" : name, systemImage: "cloud.rain.fill", coordinate: pin)
                        .tint(.blue)
                }
            }
            .mapControls {
                MapUserLocationButton()
                MapCompass()
                MapScaleView()
            }
            .onTapGesture { point in
                guard let coordinate = proxy.convert(point, from: .local) else { return }
                pin = coordinate
                Task { await suggestName(for: coordinate) }
            }
        }
        .safeAreaInset(edge: .bottom) {
            VStack(spacing: 10) {
                if pin == nil {
                    Text("Tap the map to choose a place").font(.subheadline).foregroundStyle(.secondary)
                } else {
                    TextField("Name", text: $name)
                        .textFieldStyle(.roundedBorder)
                    Button {
                        if let pin { onSave(name.isEmpty ? "Pinned place" : name, pin) }
                    } label: {
                        Text("Add this place").frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                }
            }
            .padding()
            .background(.regularMaterial)
        }
        .navigationTitle("Pick on the map")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func suggestName(for coordinate: CLLocationCoordinate2D) async {
        let location = CLLocation(latitude: coordinate.latitude, longitude: coordinate.longitude)
        guard let placemark = try? await CLGeocoder().reverseGeocodeLocation(location).first else { return }
        // Don't overwrite a pin the user has moved on from.
        guard pin?.latitude == coordinate.latitude, pin?.longitude == coordinate.longitude else { return }
        name = placemark.locality ?? placemark.name ?? placemark.administrativeArea ?? ""
    }
}
