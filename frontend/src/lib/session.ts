"use client";
import { create } from "zustand";

type Session = {
  storeToken: string; name: string; phone: string; playId: string; animationSeed: string;
  setCustomer: (storeToken: string, name: string, phone: string) => void;
  setGame: (playId: string, seed: string) => void;
};

export const useSession = create<Session>((set) => ({
  storeToken: "", name: "", phone: "", playId: "", animationSeed: "",
  setCustomer: (storeToken, name, phone) => set({ storeToken, name, phone }),
  setGame: (playId, animationSeed) => set({ playId, animationSeed }),
}));

