async function request(path, options = {}) {
  const response = await fetch(`/api${path}`, {
    ...options,
    headers: { "Content-Type": "application/json", ...options.headers },
  });
  if (!response.ok) {
    throw new Error(`La API respondió ${response.status}. Revisa los datos e intenta de nuevo.`);
  }
  return response.status === 204 || response.status === 201 ? null : response.json();
}

export const listDomains = () => request("/domains");
export const createDomain = (data) => request("/domains", { method: "POST", body: JSON.stringify(data) });
export const updateDomain = (id, data) => request(`/domains/${id}`, { method: "PUT", body: JSON.stringify(data) });
export const deleteDomain = (id) => request(`/domains/${id}`, { method: "DELETE" });

export const listNetworks = () => request("/ip-networks");
export const createNetwork = (data) => request("/ip-networks", { method: "POST", body: JSON.stringify(data) });
export const updateNetwork = (id, data) => request(`/ip-networks/${id}`, { method: "PUT", body: JSON.stringify(data) });
export const deleteNetwork = (id) => request(`/ip-networks/${id}`, { method: "DELETE" });
