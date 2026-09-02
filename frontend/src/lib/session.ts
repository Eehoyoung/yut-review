"use client";
import { create } from "zustand";

type Session = {
  storeToken: string; name: string; phone: string; verificationToken: string; playId: string; animationSeed: string;
  setCustomer: (storeToken: string, name: string, phone: string) => void;
  setVerification: (token: string) => void;
  setGame: (playId: string, seed: string) => void;
};

export const useSession = create<Session>((set) => ({
  storeToken: "", name: "", phone: "", verificationToken: "", playId: "", animationSeed: "",
  setCustomer: (storeToken, name, phone) => set({ storeToken, name, phone }),
  setVerification: (verificationToken) => set({ verificationToken }),
  setGame: (playId, animationSeed) => set({ playId, animationSeed }),
}));

