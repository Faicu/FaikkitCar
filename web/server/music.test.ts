import { describe, expect, it } from "vitest";

import { parseSongs } from "./music.ts";

const row = (id: string, title: string, second: string[]) => ({
  musicResponsiveListItemRenderer: {
    playlistItemData: { videoId: id },
    thumbnail: { musicThumbnailRenderer: { thumbnail: { thumbnails: [{ url: "https://t/" + id }] } } },
    flexColumns: [
      { musicResponsiveListItemFlexColumnRenderer: { text: { runs: [{ text: title }] } } },
      { musicResponsiveListItemFlexColumnRenderer: { text: { runs: second.map((text) => ({ text })) } } },
      { musicResponsiveListItemFlexColumnRenderer: { text: { runs: [{ text: "2,4 mld. de redări" }] } } },
    ],
  },
});

describe("parseSongs", () => {
  it("citește piesa, artistul, albumul și durata", () => {
    const data = {
      contents: {
        tabs: [
          { shelf: { contents: [row("9qnqYL0eNNI", "Yellow", ["Coldplay", " • ", "Parachutes", " • ", "4:27"])] } },
        ],
      },
    };
    expect(parseSongs(data)).toEqual([
      { id: "9qnqYL0eNNI", title: "Yellow", artist: "Coldplay", album: "Parachutes", duration: "4:27", thumb: "https://t/9qnqYL0eNNI" },
    ]);
  });

  it("fără duplicate, fără rânduri fără ID, cel mult max", () => {
    const r = row("AAAAAAAAAAA", "A", ["X", " • ", "3:00"]);
    const noId = { musicResponsiveListItemRenderer: { flexColumns: [] } };
    const many = Array.from({ length: 30 }, (_, i) => row("id" + String(i).padStart(9, "0"), "T" + i, ["Y"]));
    expect(parseSongs([r, r, noId]).map((s) => s.id)).toEqual(["AAAAAAAAAAA"]);
    expect(parseSongs([r]).at(0)).toMatchObject({ artist: "X", album: "", duration: "3:00" });
    expect(parseSongs(many, 5)).toHaveLength(5);
  });
});
